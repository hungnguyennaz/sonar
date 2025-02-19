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

package xyz.jonesdev.sonar.bungee.fallback;

import lombok.RequiredArgsConstructor;
import net.kyori.adventure.text.Component;
import net.md_5.bungee.api.event.LoginEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.event.EventPriority;
import org.jetbrains.annotations.NotNull;
import xyz.jonesdev.sonar.api.Sonar;
import xyz.jonesdev.sonar.bungee.SonarBungee;

import java.net.InetAddress;
import java.util.Objects;

@RequiredArgsConstructor
public final class FallbackListener implements Listener {

  @SuppressWarnings("deprecation")
  @EventHandler(priority = EventPriority.LOWEST)
  public void handle(final @NotNull LoginEvent event) {
    final InetAddress inetAddress = event.getConnection().getAddress().getAddress();

    // Check if the number of online players using the same IP address as
    // the connecting player is greater than the configured amount
    final int maxOnlinePerIp = Sonar.get().getConfig().getMaxOnlinePerIp();

    if (maxOnlinePerIp > 0) {
      final long onlinePerIp = SonarBungee.INSTANCE.getPlugin().getServer().getPlayers().stream()
        .filter(player -> Objects.equals(player.getAddress().getAddress(), inetAddress))
        .count()
        + 1 /* add 1 because the player hasn't been added to the list of online players yet */;

      // We use '>=' because the player connecting to the server hasn't joined yet
      if (onlinePerIp >= maxOnlinePerIp) {
        final FallbackInitialHandler fallbackInitialHandler = (FallbackInitialHandler) event.getConnection();
        final Component component = Sonar.get().getConfig().getTooManyOnlinePerIp();
        fallbackInitialHandler.closeWith(FallbackInitialHandler.getKickPacket(component));
      }
    }
  }
}
