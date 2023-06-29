/*
 * Copyright (C) 2023, jones
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

package jones.sonar.bukkit.command

import com.google.common.cache.CacheBuilder
import jones.sonar.api.Sonar
import jones.sonar.common.command.CommandInvocation
import jones.sonar.common.command.InvocationSender
import jones.sonar.common.command.subcommand.SubCommand
import jones.sonar.common.command.subcommand.SubCommandRegistry
import jones.sonar.common.command.subcommand.argument.Argument
import net.md_5.bungee.api.chat.ClickEvent
import net.md_5.bungee.api.chat.ComponentBuilder
import net.md_5.bungee.api.chat.HoverEvent
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.command.*
import org.bukkit.entity.Player
import java.text.DecimalFormat
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import java.util.stream.Collectors

@Suppress("unstableApiUsage")
class SonarCommand : CommandExecutor, TabExecutor {
  override fun onCommand(
    sender: CommandSender,
    command: Command,
    label: String,
    args: Array<String>
  ): Boolean {
    // Checking if it contains will only break more since it can throw
    // a NullPointerException if the cache is being accessed from parallel threads
    val timestamp = delay.asMap().getOrDefault(sender, -1L)
    val currentTimestamp = System.currentTimeMillis()

    // There were some exploits with spamming commands in the past,
    // Spamming should be prevented, especially if some heavy operations are done,
    // which is not the case here but let's still stay safe!
    if (timestamp > 0L) {
      sender.sendMessage(Sonar.get().config.COMMAND_COOL_DOWN)

      // Format delay
      val left = 0.5 - (currentTimestamp - timestamp.toDouble()) / 1000.0
      sender.sendMessage(
        Sonar.get().config.COMMAND_COOL_DOWN_LEFT
          .replace("%time-left%", decimalFormat.format(left))
      )
      return false
    }
    delay.put(sender, currentTimestamp)

    var subCommand = Optional.empty<SubCommand>()
    val invocationSender = InvocationSender { message -> sender.sendMessage(message) }

    if (args.isNotEmpty()) {
      // Search subcommand if command arguments are present
      subCommand = SubCommandRegistry.getSubCommands().stream()
        .filter { sub: SubCommand ->
          (sub.info.name.equals(args[0], ignoreCase = true)
            || (sub.info.aliases.isNotEmpty()
            && Arrays.stream(sub.info.aliases)
            .anyMatch { alias: String -> alias.equals(args[0], ignoreCase = true) }))
        }
        .findFirst()

      // Check permissions for subcommands
      subCommand.ifPresent { it: SubCommand ->
        if (!it.info.onlyConsole && !sender.hasPermission(it.permission)) {
          invocationSender.sendMessage(
            Sonar.get().config.SUB_COMMAND_NO_PERM
              .replace("%permission%", it.permission)
          )
        }
      }
    }

    // No subcommand was found
    if (!subCommand.isPresent) {
      invocationSender.sendMessage()
      invocationSender.sendMessage(
        " §eRunning §lSonar §e"
          + Sonar.get().version
          + " on "
          + Sonar.get().platform.displayName
      )
      val rawDiscordText = " §7Need help?§b discord.jonesdev.xyz"
      if (sender is Player) {
        val discordComponent = TextComponent(rawDiscordText)
        discordComponent.hoverEvent = HoverEvent(
          HoverEvent.Action.SHOW_TEXT, ComponentBuilder("§7Click to open Discord").create()
        )
        discordComponent.clickEvent = ClickEvent(
          ClickEvent.Action.OPEN_URL, "https://discord.jonesdev.xyz/"
        )
        sender.spigot().sendMessage(discordComponent)
      } else {
        sender.sendMessage(rawDiscordText)
      }
      invocationSender.sendMessage()

      SubCommandRegistry.getSubCommands().forEach(Consumer { sub: SubCommand ->
        val rawText = (" §a▪ §7/sonar "
          + sub.info.name
          + " §f"
          + sub.info.description)
        if (sender is Player) {
          val component = TextComponent(rawText)
          component.clickEvent =
            ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/sonar " + sub.info.name + " ")

          component.hoverEvent = HoverEvent(
            HoverEvent.Action.SHOW_TEXT, ComponentBuilder(
              """
              §7Only players: §f${if (sub.info.onlyPlayers) "§a✔" else "§c✗"}
              §7Only console: §f${if (sub.info.onlyConsole) "§a✔" else "§c✗"}
              §7Permission: §f${sub.permission}
              §7(Click to run)
              """.trimIndent()
            ).create()
          )
          sender.spigot().sendMessage(component)
        } else {
          sender.sendMessage(rawText)
        }
      })

      invocationSender.sendMessage()
      return false
    }

    // ifPresentOrElse() doesn't exist yet... (version compatibility)
    subCommand.ifPresent { sub: SubCommand ->
      if (sub.info.onlyPlayers && sender !is Player) {
        invocationSender.sendMessage(Sonar.get().config.PLAYERS_ONLY)
        return@ifPresent
      }

      if (sub.info.onlyConsole && sender !is ConsoleCommandSender) {
        invocationSender.sendMessage(Sonar.get().config.CONSOLE_ONLY)
        return@ifPresent
      }

      val commandInvocation = CommandInvocation(
        sender.name,
        invocationSender,
        sub,
        args
      )

      // The subcommands has arguments which are not present in the executed command
      if (sub.info.arguments.isNotEmpty()
        && commandInvocation.arguments.size <= 1
      ) {
        invocationSender.sendMessage(
          Sonar.get().config.INCORRECT_COMMAND_USAGE
            .replace("%usage%", sub.info.name + " (" + sub.arguments + ")")
        )
        return@ifPresent
      }

      // Execute the sub command with the custom invocation properties
      sub.execute(commandInvocation)
    }
    return false
  }

  // Tab completion handling
  override fun onTabComplete(
    sender: CommandSender, command: Command,
    alias: String, args: Array<String>
  ): List<String> {
    return if (args.size <= 1) {
      if (TAB_SUGGESTIONS.isEmpty()) {
        for (subCommand in SubCommandRegistry.getSubCommands()) {
          TAB_SUGGESTIONS.add(subCommand.info.name)
          if (subCommand.info.aliases.isNotEmpty()) {
            TAB_SUGGESTIONS.addAll(listOf(*subCommand.info.aliases))
          }
        }
      }
      TAB_SUGGESTIONS
    } else if (args.size == 2) {
      if (ARG_TAB_SUGGESTIONS.isEmpty()) {
        for (subCommand in SubCommandRegistry.getSubCommands()) {
          ARG_TAB_SUGGESTIONS[subCommand.info.name] = Arrays.stream(subCommand.info.arguments)
            .map { obj: Argument -> obj.name }
            .collect(Collectors.toList())
        }
      }

      val subCommandName = args[0].lowercase(Locale.getDefault())
      ARG_TAB_SUGGESTIONS.getOrDefault(subCommandName, emptyList())
    } else emptyList()
  }

  companion object {
    private val delay = CacheBuilder.newBuilder()
      .expireAfterWrite(500L, TimeUnit.MILLISECONDS)
      .build<CommandSender, Long>()
    private val decimalFormat = DecimalFormat("#.##")
    private val TAB_SUGGESTIONS: MutableList<String> = ArrayList()
    private val ARG_TAB_SUGGESTIONS: MutableMap<String, List<String>> = HashMap()
  }
}
