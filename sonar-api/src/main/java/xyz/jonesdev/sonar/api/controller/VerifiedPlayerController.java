/*
 * Copyright (C) 2023 Sonar Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package xyz.jonesdev.sonar.api.controller;

import com.j256.ormlite.dao.Dao;
import com.j256.ormlite.dao.DaoManager;
import com.j256.ormlite.stmt.QueryBuilder;
import com.j256.ormlite.support.ConnectionSource;
import com.j256.ormlite.table.TableUtils;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Range;
import xyz.jonesdev.sonar.api.Sonar;
import xyz.jonesdev.sonar.api.config.SonarConfiguration;
import xyz.jonesdev.sonar.api.dependencies.DependencyLoader;
import xyz.jonesdev.sonar.api.model.VerifiedPlayer;

import java.net.InetAddress;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class VerifiedPlayerController {
  private static final Map<String, Collection<UUID>> MAP = new ConcurrentHashMap<>();
  private static final ExecutorService DB_UPDATE_SERVICE = Executors.newSingleThreadExecutor();

  private @Nullable ConnectionSource connectionSource;
  private Dao<VerifiedPlayer, Integer> dao;
  private QueryBuilder<VerifiedPlayer, Integer> queryBuilder;
  @Getter
  private final @NotNull SonarConfiguration.Database.Type cachedDatabaseType;

  public VerifiedPlayerController() {
    // Cache selected database type, so we don't need to call Sonar.get() every time
    cachedDatabaseType = Sonar.get().getConfig().getDatabase().getType();

    // Don't establish a database connection if the type is NONE
    if (cachedDatabaseType == SonarConfiguration.Database.Type.NONE) {
      Sonar.get().getLogger().warn("Make sure to configure a database to save verified players.");
      return;
    }

    try (final ConnectionSource connectionSource = DependencyLoader.setUpDriverAndConnect()) {
      this.connectionSource = connectionSource;

      // Create table
      try {
        TableUtils.createTableIfNotExists(connectionSource, VerifiedPlayer.class);
      } catch (SQLException exception) {
        // Duplicate index
        // I know this isn't the best method of handling it,
        // but I don't know how else I could address this issue.
      }

      dao = DaoManager.createDao(connectionSource, VerifiedPlayer.class);
      queryBuilder = dao.queryBuilder();

      // Make sure to clear all outdated entries first
      clearOld(Sonar.get().getConfig().getDatabase().getMaximumAge());
      // Add all entries from the database to the cache
      dao.queryForAll().forEach(this::_add);
    } catch (Throwable throwable) {
      Sonar.get().getLogger().error("Error setting up database connection: {}", throwable);
      throwable.printStackTrace(System.err);
    }
  }

  /**
   * First, remove the player from the local cache and then,
   * secondly, asynchronously add the player to the database.
   *
   * @param inetAddress InetAddress of the VerifiedPlayer model
   */
  public void remove(final @NotNull String inetAddress) {
    _remove(inetAddress);

    // Don't try to update the column if the database type is NONE
    if (cachedDatabaseType == SonarConfiguration.Database.Type.NONE) {
      return;
    }

    DB_UPDATE_SERVICE.execute(() -> {
      // We cannot throw a NullPointerException within the executor service
      // because we want to handle the error instead of simply throwing an exception
      if (connectionSource == null) {
        return;
      }

      try {
        final List<VerifiedPlayer> verifiedPlayer = queryBuilder.where()
          .eq("ip_address", inetAddress)
          .query();

        if (verifiedPlayer != null) {
          for (final VerifiedPlayer player : verifiedPlayer) {
            dao.delete(player);
          }
        }
      } catch (SQLException exception) {
        Sonar.get().getLogger().error("Error trying to remove entry: {}", exception);
      }
    });
  }

  /**
   * Locally remove the object from the cache
   *
   * @param inetAddress IP address of the player
   */
  private void _remove(final @NotNull String inetAddress) {
    MAP.remove(inetAddress);
  }

  /**
   * First, cache the player locally and then,
   * secondly, asynchronously add the player to the database.
   *
   * @param player VerifiedPlayer model
   */
  public void add(final @NotNull VerifiedPlayer player) {
    _add(player);

    // Don't try to update the column if the database type is NONE
    if (cachedDatabaseType == SonarConfiguration.Database.Type.NONE) {
      return;
    }

    DB_UPDATE_SERVICE.execute(() -> {
      // We cannot throw a NullPointerException within the executor service
      // because we want to handle the error instead of simply throwing an exception
      if (connectionSource == null) {
        return;
      }

      try {
        dao.create(player);
      } catch (SQLException exception) {
        Sonar.get().getLogger().error("Error trying to add entry: {}", exception);
      }
    });
  }

  /**
   * Locally cache the object
   *
   * @param player VerifiedPlayer model
   */
  private void _add(final @NotNull VerifiedPlayer player) {
    MAP.computeIfAbsent(player.getInetAddress(), v -> new Vector<>())
      .add(player.getPlayerUUID());
  }

  /**
   * @return Estimated size of the local cache
   */
  public synchronized int estimatedSize() {
    return MAP.values().stream()
      .mapToInt(Collection::size)
      .sum();
  }

  /**
   * @return List of UUIDs associated with one IP address
   */
  public Collection<UUID> getUUIDs(final @NotNull String inetAddress) {
    return MAP.getOrDefault(inetAddress, Collections.emptyList());
  }

  /**
   * Clear the local cache, and then if the database type is set,
   * remove the table from the database.
   */
  public void clearAll() {
    try {
      MAP.clear();

      // Only update the column if the database type is not NONE
      if (cachedDatabaseType != SonarConfiguration.Database.Type.NONE) {
        dao.deleteBuilder().delete();
      }
    } catch (SQLException exception) {
      Sonar.get().getLogger().error("Error trying to clear entries: {}", exception);
    }
  }

  /**
   * Clear all old entries using the given timestamp.
   */
  public void clearOld(final @Range(from = 1, to = 365) int maximumAge) {
    // Don't try to update the column if the database type is NONE
    if (cachedDatabaseType == SonarConfiguration.Database.Type.NONE) {
      return;
    }

    DB_UPDATE_SERVICE.execute(() -> {
      // We cannot throw a NullPointerException within the executor service
      // because we want to handle the error instead of simply throwing an exception
      if (connectionSource == null) {
        return;
      }

      try {
        final long timestamp = Instant.now()
          .minus(maximumAge, ChronoUnit.DAYS)
          .getEpochSecond() * 1000L; // convert to ms

        final List<VerifiedPlayer> oldEntries = queryBuilder.where()
          .lt("timestamp", new Timestamp(timestamp))
          .query();

        if (oldEntries != null) {
          for (final VerifiedPlayer player : oldEntries) {
            dao.delete(player);
          }
          Sonar.get().getLogger().info("Removed {} database entries older than {} days.",
            oldEntries.size(), maximumAge);
        }
      } catch (SQLException exception) {
        Sonar.get().getLogger().error("Error trying to clear old entries: {}", exception);
      }
    });
  }

  /**
   * @param inetAddress IP address
   * @param uuid        UUID associated to the IP
   * @return Whether the local cache contains the IP and UUID
   */
  public boolean has(final @NotNull String inetAddress, final @NotNull UUID uuid) {
    final Collection<UUID> got = MAP.get(inetAddress);
    if (got != null) {
      return got.contains(uuid);
    }
    return false;
  }

  /**
   * @param inetAddress IP address
   * @param uuid        UUID associated to the IP
   * @return Whether the local cache contains the IP and UUID
   * @see #has(String, UUID)
   */
  public boolean has(final @NotNull InetAddress inetAddress, final @NotNull UUID uuid) {
    return has(inetAddress.toString(), uuid);
  }

  /**
   * @param inetAddress IP address
   * @return Whether the local cache contains the IP
   */
  public boolean has(final @NotNull String inetAddress) {
    return MAP.containsKey(inetAddress);
  }

  /**
   * @param inetAddress IP address
   * @return Whether the local cache contains the IP
   * @see #has(String)
   */
  public boolean has(final @NotNull InetAddress inetAddress) {
    return has(inetAddress.toString());
  }
}
