package emu.grasscutter.command;

import emu.grasscutter.Grasscutter;
import emu.grasscutter.game.player.Player;
import org.reflections.Reflections;

import java.util.*;

@SuppressWarnings({"UnusedReturnValue", "unused"})
public final class CommandMap {
    private final Map<String, CommandHandler> commands = new HashMap<>();
    private final Map<String, CommandHandler> aliases = new HashMap<>();
    private final Map<String, Command> annotations = new HashMap<>();
    private final Map<String, Integer> targetPlayerIds = new HashMap<>();
    private static final String consoleId = "console";

    public CommandMap() {
        this(false);
    }

    public CommandMap(boolean scan) {
        if (scan) this.scan();
    }

    public static CommandMap getInstance() {
        return Grasscutter.getGameServer().getCommandMap();
    }

    /**
     * Register a command handler.
     *
     * @param label   The command label.
     * @param command The command handler.
     * @return Instance chaining.
     */
    public CommandMap registerCommand(String label, CommandHandler command) {
        Grasscutter.getLogger().debug("Registered command: " + label);

        // Get command data.
        Command annotation = command.getClass().getAnnotation(Command.class);
        this.annotations.put(label, annotation);
        this.commands.put(label, command);

        // Register aliases.
        if (annotation.aliases().length > 0) {
            for (String alias : annotation.aliases()) {
                this.aliases.put(alias, command);
                this.annotations.put(alias, annotation);
            }
        }
        return this;
    }

    /**
     * Removes a registered command handler.
     *
     * @param label The command label.
     * @return Instance chaining.
     */
    public CommandMap unregisterCommand(String label) {
        Grasscutter.getLogger().debug("Unregistered command: " + label);

        CommandHandler handler = this.commands.get(label);
        if (handler == null) return this;

        Command annotation = handler.getClass().getAnnotation(Command.class);
        this.annotations.remove(label);
        this.commands.remove(label);

        // Unregister aliases.
        if (annotation.aliases().length > 0) {
            for (String alias : annotation.aliases()) {
                this.aliases.remove(alias);
                this.annotations.remove(alias);
            }
        }

        return this;
    }

    public List<Command> getAnnotationsAsList() {
        return new LinkedList<>(this.annotations.values());
    }

    public HashMap<String, Command> getAnnotations() {
        return new LinkedHashMap<>(this.annotations);
    }

    /**
     * Returns a list of all registered commands.
     *
     * @return All command handlers as a list.
     */
    public List<CommandHandler> getHandlersAsList() {
        return new LinkedList<>(this.commands.values());
    }

    public HashMap<String, CommandHandler> getHandlers() {
        return new LinkedHashMap<>(this.commands);
    }

    /**
     * Returns a handler by label/alias.
     *
     * @param label The command label.
     * @return The command handler.
     */
    public CommandHandler getHandler(String label) {
        return this.commands.get(label);
    }

    /**
     * Invoke a command handler with the given arguments.
     *
     * @param player     The player invoking the command or null for the server console.
     * @param rawMessage The messaged used to invoke the command.
     */
    public void invoke(Player player, Player targetPlayer, String rawMessage) {
        rawMessage = rawMessage.trim();
        if (rawMessage.length() == 0) {
            CommandHandler.sendTranslatedMessage(player, "commands.generic.not_specified");
            return;
        }

        // Parse message.
        String[] split = rawMessage.split(" ");
        List<String> args = new LinkedList<>(Arrays.asList(split));
        String label = args.remove(0);
        String playerId = (player == null) ? consoleId : player.getAccount().getId();

        // Check for special cases - currently only target command.
        String targetUidStr = null;
        if (label.startsWith("@")) { // @[UID]
            targetUidStr = label.substring(1);
        } else if (label.equalsIgnoreCase("target")) { // target [[@]UID]
            if (args.size() > 0) {
                targetUidStr = args.get(0);
                if (targetUidStr.startsWith("@")) {
                    targetUidStr = targetUidStr.substring(1);
                }
            } else {
                targetUidStr = "";
            }
        }
        if (targetUidStr != null) {
            if (targetUidStr.equals("")) { // Clears the default targetPlayer.
                this.targetPlayerIds.remove(playerId);
                CommandHandler.sendTranslatedMessage(player, "commands.execution.clear_target");
            } else { // Sets default targetPlayer to the UID provided.
                try {
                    int uid = Integer.parseInt(targetUidStr);
                    targetPlayer = Grasscutter.getGameServer().getPlayerByUid(uid, true);
                    if (targetPlayer == null) {
                        CommandHandler.sendTranslatedMessage(player, "commands.execution.player_exist_error");
                    } else {
                        this.targetPlayerIds.put(playerId, uid);
                        CommandHandler.sendTranslatedMessage(player, "commands.execution.set_target", targetUidStr);
                        CommandHandler.sendTranslatedMessage(player, targetPlayer.isOnline() ? "commands.execution.set_target_online" : "commands.execution.set_target_offline", targetUidStr);
                    }
                } catch (NumberFormatException e) {
                    CommandHandler.sendTranslatedMessage(player, "commands.generic.invalid.uid");
                }
            }
            return;
        }

        // Get command handler.
        CommandHandler handler = this.commands.get(label);
        if(handler == null)
            // Try to get the handler by alias.
            handler = this.aliases.get(label);

        // Check if the handler is still null.
        if (handler == null) {
            CommandHandler.sendTranslatedMessage(player, "commands.generic.unknown_command", label);
            return;
        }

        // Get the command's annotation.
        Command annotation = this.annotations.get(label);

        // If any @UID argument is present, override targetPlayer with it.
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (arg.startsWith("@")) {
                arg = args.remove(i).substring(1);
                try {
                    int uid = Integer.parseInt(arg);
                    targetPlayer = Grasscutter.getGameServer().getPlayerByUid(uid, true);
                    if (targetPlayer == null) {
                        CommandHandler.sendTranslatedMessage(player, "commands.execution.player_exist_error");
                        return;
                    }
                    break;
                } catch (NumberFormatException e) {
                    CommandHandler.sendTranslatedMessage(player, "commands.generic.invalid.uid");
                    return;
                }
            }
        }

        // If there's still no targetPlayer at this point, use previously-set target
        if (targetPlayer == null) {
            if (this.targetPlayerIds.containsKey(playerId)) {
                targetPlayer = Grasscutter.getGameServer().getPlayerByUid(this.targetPlayerIds.get(playerId), true);  // We check every time in case the target is deleted after being targeted
                if (targetPlayer == null) {
                    CommandHandler.sendTranslatedMessage(player, "commands.execution.player_exist_error");
                    return;
                }
            } else {
                // If there's still no targetPlayer at this point, use executor.
                targetPlayer = player;
            }
        }

        // Check for permissions.
        if (!Grasscutter.getPermissionHandler().checkPermission(player, targetPlayer, annotation.permission(), this.annotations.get(label).permissionTargeted())) {
            return;
        }

        // Check if command has unfulfilled constraints on targetPlayer
        Command.TargetRequirement targetRequirement = annotation.targetRequirement();
        if (targetRequirement != Command.TargetRequirement.NONE) {
            if (targetPlayer == null) {
                CommandHandler.sendTranslatedMessage(null, "commands.execution.need_target");
                return;
            }

            if ((targetRequirement == Command.TargetRequirement.ONLINE) && !targetPlayer.isOnline()) {
                CommandHandler.sendTranslatedMessage(player, "commands.execution.need_target_online");
                return;
            }

            if ((targetRequirement == Command.TargetRequirement.OFFLINE) && targetPlayer.isOnline()) {
                CommandHandler.sendTranslatedMessage(player, "commands.execution.need_target_offline");
                return;
            }
        }

        // Copy player and handler to final properties.
        final Player targetPlayerF = targetPlayer; // Is there a better way to do this?
        final CommandHandler handlerF = handler; // Is there a better way to do this?

        // Invoke execute method for handler.
        Runnable runnable = () -> handlerF.execute(player, targetPlayerF, args);
        if (annotation.threading()) {
            new Thread(runnable).start();
        } else {
            runnable.run();
        }
    }

    /**
     * Scans for all classes annotated with {@link Command} and registers them.
     */
    private void scan() {
        Reflections reflector = Grasscutter.reflector;
        Set<Class<?>> classes = reflector.getTypesAnnotatedWith(Command.class);

        classes.forEach(annotated -> {
            try {
                Command cmdData = annotated.getAnnotation(Command.class);
                Object object = annotated.getDeclaredConstructor().newInstance();
                if (object instanceof CommandHandler)
                    this.registerCommand(cmdData.label(), (CommandHandler) object);
                else Grasscutter.getLogger().error("Class " + annotated.getName() + " is not a CommandHandler!");
            } catch (Exception exception) {
                Grasscutter.getLogger().error("Failed to register command handler for " + annotated.getSimpleName(), exception);
            }
        });
    }
}
