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

import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import io.netty.util.ReferenceCountUtil;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.kyori.adventure.text.Component;
import net.md_5.bungee.netty.ChannelWrapper;
import org.jetbrains.annotations.NotNull;
import xyz.jonesdev.sonar.api.fallback.Fallback;
import xyz.jonesdev.sonar.api.fallback.FallbackUser;
import xyz.jonesdev.sonar.api.fallback.protocol.ProtocolVersion;
import xyz.jonesdev.sonar.common.fallback.protocol.packets.play.Disconnect;

import java.net.InetAddress;

@Getter
@RequiredArgsConstructor
public final class FallbackUserWrapper implements FallbackUser<ChannelWrapper, FallbackInitialHandler> {
  private final Fallback fallback;
  private final ChannelWrapper player;
  private final FallbackInitialHandler connection;
  private final Channel channel;
  private final ChannelPipeline pipeline;
  private final InetAddress inetAddress;
  private final ProtocolVersion protocolVersion;

  @Override
  public void disconnect(final @NotNull Component reason) {
    connection.closeWith(Disconnect.create(reason));
  }

  @Override
  public void write(final @NotNull Object packet) {
    if (channel.isActive()) {
      channel.writeAndFlush(packet, channel.voidPromise());
    } else {
      ReferenceCountUtil.release(packet);
    }
  }

  @Override
  public void delayedWrite(final @NotNull Object packet) {
    if (channel.isActive()) {
      channel.write(packet, channel.voidPromise());
    } else {
      ReferenceCountUtil.release(packet);
    }
  }
}
