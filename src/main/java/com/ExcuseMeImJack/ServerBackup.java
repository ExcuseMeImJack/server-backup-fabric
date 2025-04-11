package com.ExcuseMeImJack;

import net.fabricmc.api.*;
import net.fabricmc.fabric.api.command.v2.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.minecraft.entity.*;
import net.minecraft.item.*;
import net.minecraft.nbt.*;
import net.minecraft.network.packet.s2c.play.*;
import net.minecraft.server.*;
import net.minecraft.server.command.*;
import net.minecraft.server.network.*;
import net.minecraft.text.*;
import net.minecraft.util.*;
import org.slf4j.*;

import com.google.common.reflect.*;
import com.google.gson.*;
import com.mojang.brigadier.*;
import com.mojang.brigadier.arguments.*;
import com.mojang.brigadier.context.*;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.datafixers.util.Pair;

import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.text.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class ServerBackup implements ModInitializer {

	// Constants
	private static final int TICKS_PER_MINUTE = 1200;
	private static final String BACKUP_HISTORY_FILE = "backup_history.json";
	public static final String MOD_ID = "ServerBackup";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	private static final int MAX_BACKUPS = 5;

	// Fields
	private int ticksSinceLastBackup = 0;
	private int backupDelayMinutes = 10;
	private Map<String, Path> backupHistory = new HashMap<>();

	// Initialization
	@Override
	public void onInitialize() {
		loadBackupHistory();
		registerCommands();
		registerTickEvent();
	}

	// Section: Command Registration
	private void registerCommands() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			registerBackupCommand(dispatcher);
			registerAutoDelayCommand(dispatcher);
			registerRestoreCommand(dispatcher);
			registerRestorePlayerCommand(dispatcher);
			registerListBackupsCommand(dispatcher);
		});
	}

	private void registerBackupCommand(CommandDispatcher<ServerCommandSource> dispatcher) {
		dispatcher.register(CommandManager.literal("serverbackup")
				.requires(source -> source.hasPermissionLevel(4))
				.executes(this::runBackupCommand));
	}

	private void registerAutoDelayCommand(CommandDispatcher<ServerCommandSource> dispatcher) {
		dispatcher.register(CommandManager.literal("serverbackup")
				.then(CommandManager.literal("autodelay")
						.then(CommandManager.argument("time", IntegerArgumentType.integer())
								.executes(this::setBackupDelay))));
	}

	private void registerRestoreCommand(CommandDispatcher<ServerCommandSource> dispatcher) {
		dispatcher.register(CommandManager.literal("serverbackup")
				.then(CommandManager.literal("restoreworld")
						.then(CommandManager.argument("backupID", StringArgumentType.string())
								.suggests(this::suggestBackupIDs)
								.executes(this::restoreWorld))));
	}

	private void registerRestorePlayerCommand(CommandDispatcher<ServerCommandSource> dispatcher) {
		dispatcher.register(CommandManager.literal("serverbackup")
				.then(CommandManager.literal("restoreplayer")
						.then(CommandManager.argument("backupID", StringArgumentType.string())
								.suggests(this::suggestBackupIDs)
								.then(CommandManager.argument("player", StringArgumentType.string())
										.suggests(this::suggestOnlinePlayers)
										.executes(this::restorePlayerInventory)))));
	}

	private void registerListBackupsCommand(CommandDispatcher<ServerCommandSource> dispatcher) {
		dispatcher.register(CommandManager.literal("serverbackup")
				.then(CommandManager.literal("list")
						.executes(this::listBackups)));
	}

	// Section: Command Suggestions
	private CompletableFuture<Suggestions> suggestBackupIDs(CommandContext<ServerCommandSource> context,
			SuggestionsBuilder builder) {
		List<Map.Entry<String, Path>> sortedBackups = new ArrayList<>(backupHistory.entrySet());

		sortedBackups.sort(Comparator.comparing(entry -> {
			try {
				if (Files.exists(entry.getValue())) {
					return Files.getLastModifiedTime(entry.getValue());
				} else {
					LOGGER.warn("Backup path does not exist: " + entry.getValue());
					return FileTime.fromMillis(0);
				}
			} catch (IOException e) {
				LOGGER.error("Failed to get last modified time for: " + entry.getValue(), e);
				return FileTime.fromMillis(0);
			}
		}));

		for (Map.Entry<String, Path> entry : sortedBackups) {
			String backupName = entry.getKey();
			if (!backupName.startsWith("manual_") && !backupName.startsWith("auto_")) {
				builder.suggest(backupName);
			}
		}

		return builder.buildFuture();
	}

	private CompletableFuture<Suggestions> suggestOnlinePlayers(CommandContext<ServerCommandSource> context,
			SuggestionsBuilder builder) {
		MinecraftServer server = context.getSource().getServer();
		for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
			builder.suggest(player.getName().getString());
		}
		return builder.buildFuture();
	}

	// Section: Backup Management
	private void backupWorld(MinecraftServer server, String backupType) throws IOException {
		Path serverDir = server.getRunDirectory().toAbsolutePath();

		Path backupsDir = serverDir.resolve("server_backups")
				.resolve(backupType.equals("manual") ? "manual_backups" : "auto_backups");

		Files.createDirectories(backupsDir);

		String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
		String backupID = generateRandomSaveId();

		Path backupDest = backupsDir.resolve(timestamp + "_" + backupID);
		Path worldDir = server.getSavePath(WorldSavePath.ROOT);
		Path tempBackupDir = Files.createTempDirectory("world_backup_");

		LOGGER.info("Starting world backup to: " + backupDest);

		try {
			copyDirectory(worldDir, tempBackupDir);
			Files.move(tempBackupDir, backupDest, StandardCopyOption.REPLACE_EXISTING);
			backupHistory.put(backupID, backupDest);

			if (backupType.equals("manual")) {
				limitBackups(backupsDir, 5);
			}
		} catch (IOException e) {
			LOGGER.error("Backup failed", e);
			throw e;
		} finally {
			if (Files.exists(tempBackupDir)) {
				deleteDirectory(tempBackupDir);
			}
		}

		saveBackupHistory();

		if (backupType.equals("auto")) {
			limitBackups(backupsDir, MAX_BACKUPS);
		}

		LOGGER.info("World backup completed.");
	}

	private void saveBackupHistory() {
		Gson gson = new Gson();
		Map<String, String> backupHistoryAsStrings = new HashMap<>();

		for (Map.Entry<String, Path> entry : backupHistory.entrySet()) {
			backupHistoryAsStrings.put(entry.getKey(), entry.getValue().toString());
		}

		try (FileWriter writer = new FileWriter(BACKUP_HISTORY_FILE)) {
			gson.toJson(backupHistoryAsStrings, writer);
		} catch (IOException e) {
			LOGGER.error("Failed to save backup history", e);
		}
	}

	private void loadBackupHistory() {
    Gson gson = new Gson();
    File backupHistoryFile = new File(BACKUP_HISTORY_FILE);

    if (!backupHistoryFile.exists()) {
        try {
            if (backupHistoryFile.createNewFile()) {
                LOGGER.info("Backup history file created.");
            } else {
                LOGGER.error("Failed to create backup history file.");
            }
        } catch (IOException e) {
            LOGGER.error("Error creating backup history file.", e);
        }
    }

    try (FileReader reader = new FileReader(backupHistoryFile)) {
        if (reader.ready()) {
            Type type = new TypeToken<Map<String, String>>() {}.getType();
            Map<String, String> backupHistoryAsStrings = gson.fromJson(reader, type);
            backupHistory.clear();
            for (Map.Entry<String, String> entry : backupHistoryAsStrings.entrySet()) {
                Path backupPath = Paths.get(entry.getValue());
                if (Files.exists(backupPath) && Files.isDirectory(backupPath)) {
                    backupHistory.put(entry.getKey(), backupPath);
                } else {
                    LOGGER.warn("Backup directory no longer exists: " + backupPath);
                }
            }
        } else {
            LOGGER.info("Backup history file is empty. Initializing with an empty history.");
            backupHistory.clear();
        }
    } catch (JsonSyntaxException e) {
        LOGGER.error("Invalid JSON format in backup history file. Reinitializing with an empty history.", e);
        backupHistory.clear();
        resetBackupHistoryFile();
    } catch (IOException e) {
        LOGGER.error("Error reading the backup history file.", e);
        backupHistory.clear();
    }
}

	private void resetBackupHistoryFile() {
		Gson gson = new Gson();
		try (FileWriter writer = new FileWriter(BACKUP_HISTORY_FILE)) {
			Map<String, String> emptyHistory = new HashMap<>();
			gson.toJson(emptyHistory, writer);
			LOGGER.info("Backup history file has been reset with an empty history.");
		} catch (IOException e) {
			LOGGER.error("Failed to reset the backup history file.", e);
		}
	}

	private void limitBackups(Path backupsDir, int maxBackups) throws IOException {
		List<Path> backups = Files.list(backupsDir)
				.filter(Files::isDirectory)
				.sorted(Comparator.comparing(path -> {
					try {
						return Files.getLastModifiedTime(path);
					} catch (IOException e) {
						LOGGER.error("Failed to get last modified time for: " + path, e);
						return FileTime.fromMillis(0);
					}
				}))
				.toList();

		while (backups.size() > maxBackups) {
			Path oldestBackup = backups.get(0);
			deleteDirectory(oldestBackup);
			LOGGER.info("Deleted old backup: " + oldestBackup.getFileName());
			backups = backups.subList(1, backups.size());
		}
	}

	private int runBackupCommand(CommandContext<ServerCommandSource> context) {
		MinecraftServer server = context.getSource().getServer();
		context.getSource()
				.sendMessage(Text.literal("Starting manual backup...").setStyle(Style.EMPTY.withColor(Formatting.AQUA)));

		server.execute(() -> {
			try {
				LOGGER.info("Executing save-all command...");
				String command = "save-all";
				ParseResults<ServerCommandSource> parseResults = server.getCommandManager().getDispatcher().parse(command,
						server.getCommandSource());
				server.getCommandManager().getDispatcher().execute(parseResults);
				LOGGER.info("World and player data saved successfully.");
			} catch (Exception e) {
				LOGGER.error("Failed to execute save-all command", e);
			}
		});

		new Thread(() -> {
			try {
				backupWorld(server, "manual");

				context.getSource().sendMessage(
						Text.literal("Manual backup completed successfully.")
								.setStyle(Style.EMPTY.withColor(Formatting.GREEN)));
			} catch (Exception e) {
				LOGGER.error("Error during backup process", e);
				context.getSource().sendError(
						Text.literal("Error during backup process: " + e.getMessage())
								.setStyle(Style.EMPTY.withColor(Formatting.RED)));
			}
		}).start();

		return 1;
	}

	private int setBackupDelay(CommandContext<ServerCommandSource> context) {
		int delay = IntegerArgumentType.getInteger(context, "time");
		this.backupDelayMinutes = delay;
		context.getSource().sendMessage(Text.literal("Automatic backup delay set to " + delay + " minutes.")
				.setStyle(Style.EMPTY.withColor(Formatting.AQUA)));
		return 1;
	}

	private int listBackups(CommandContext<ServerCommandSource> context) {
		File manualBackupsFolder = new File("server_backups/manual_backups");
		File autoBackupsFolder = new File("server_backups/auto_backups");

		Map<String, Path> backupHistory = new HashMap<>();

		if (manualBackupsFolder.exists() && manualBackupsFolder.isDirectory()) {
			for (File backupDir : manualBackupsFolder.listFiles(File::isDirectory)) {
				backupHistory.put("manual_" + backupDir.getName(), backupDir.toPath());
			}
		}

		if (autoBackupsFolder.exists() && autoBackupsFolder.isDirectory()) {
			for (File backupDir : autoBackupsFolder.listFiles(File::isDirectory)) {
				backupHistory.put("auto_" + backupDir.getName(), backupDir.toPath());
			}
		}

		if (backupHistory.isEmpty()) {
			context.getSource().sendMessage(Text.literal("No backups found.")
					.setStyle(Style.EMPTY.withColor(Formatting.YELLOW)));
			return 1;
		}

		List<Map.Entry<String, Path>> sortedBackups = new ArrayList<>(backupHistory.entrySet());
		sortedBackups.sort(Comparator.comparing(entry -> {
			try {
				return Files.getLastModifiedTime(entry.getValue());
			} catch (IOException e) {
				LOGGER.error("Failed to get last modified time for: " + entry.getValue(), e);
				return FileTime.fromMillis(0);
			}
		}));

		Text listBuilder = Text.literal("T | ID       | Date           | Time\n")
				.setStyle(Style.EMPTY.withColor(Formatting.GOLD))
				.append(Text.literal("-------------------------------\n")
						.setStyle(Style.EMPTY.withColor(Formatting.GRAY)));

		for (Map.Entry<String, Path> entry : sortedBackups) {
			String backupType = entry.getKey().startsWith("manual") ? "M" : "A";
			String backupName = entry.getKey().substring(backupType.equals("M") ? 7 : 5);

			String[] parts = backupName.split("_");
			if (parts.length < 3) {
				continue;
			}

				String backupDate = parts[0].replace("-", "/");
				String backupTime = parts[1].replace("-", ":");
				String backupID = parts[2];

				Text backupEntry = Text.literal("")
						.append(Text.literal(backupType)
								.setStyle(Style.EMPTY.withColor(backupType.equals("M") ? Formatting.RED : Formatting.GREEN)))
						.append(Text.literal(" | ").setStyle(Style.EMPTY.withColor(Formatting.WHITE)))
						.append(Text.literal(backupID)
								.setStyle(Style.EMPTY.withColor(Formatting.YELLOW)))
						.append(Text.literal(" | " + backupDate + " | " + backupTime + "\n")
								.setStyle(Style.EMPTY.withColor(Formatting.WHITE)));

				listBuilder = listBuilder.copy().append(backupEntry);
			}

		context.getSource().sendMessage(listBuilder);
		return 1;
	}

	// Section: Restore Management
	private int restoreWorld(CommandContext<ServerCommandSource> context) {
		String backupID = StringArgumentType.getString(context, "backupID");
		MinecraftServer server = context.getSource().getServer();

		Path backupPath = backupHistory.get(backupID);
		if (backupPath == null) {
			context.getSource().sendError(
					Text.literal("No backup found with ID " + backupID).setStyle(Style.EMPTY.withColor(Formatting.YELLOW)));
			return 0;
		}

		context.getSource().sendMessage(
				Text.literal("Restoring world from backup " + backupID).setStyle(Style.EMPTY.withColor(Formatting.AQUA)));

		for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
			player.networkHandler.disconnect(Text.literal("The world is being restored. Please reconnect shortly.")
					.setStyle(Style.EMPTY.withColor(Formatting.YELLOW)));
		}

		try {
			restoreBackup(server, backupPath);
			context.getSource()
					.sendMessage(Text.literal("World restored successfully.").setStyle(Style.EMPTY.withColor(Formatting.GREEN)));
		} catch (IOException e) {
			LOGGER.error("Failed to restore world", e);
			context.getSource().sendError(
					Text.literal("Failed to restore world: " + e.getMessage()).setStyle(Style.EMPTY.withColor(Formatting.RED)));
			return 0;
		}

		server.execute(() -> {
			server.getPlayerManager().broadcast(Text.literal("Server is restarting to load the restored world..."), false);
			server.stop(false);
		});

		return 1;
	}

	private void restoreBackup(MinecraftServer server, Path backupPath) throws IOException {
		Path worldDir = server.getSavePath(WorldSavePath.ROOT);
		deleteDirectory(worldDir);
		copyDirectory(backupPath, worldDir);
	}

	private int restorePlayerInventory(CommandContext<ServerCommandSource> context) {
		String backupID = StringArgumentType.getString(context, "backupID");
		String playerName = StringArgumentType.getString(context, "player");

		Path backupPath = backupHistory.get(backupID);
		if (backupPath == null) {
			context.getSource().sendError(
					Text.literal("No backup found with ID " + backupID).setStyle(Style.EMPTY.withColor(Formatting.YELLOW)));
			return 0;
		}

		context.getSource()
				.sendMessage(Text.literal("Restoring inventory for player " + playerName + " from backup " + backupID)
						.setStyle(Style.EMPTY.withColor(Formatting.AQUA)));

		UUID playerUUID = getPlayerUUID(playerName, context.getSource());
		if (playerUUID == null) {
			context.getSource()
					.sendError(Text.literal("Player not found: " + playerName).setStyle(Style.EMPTY.withColor(Formatting.RED)));
			return 0;
		}

		Path playerFile = backupPath.resolve("playerdata").resolve(playerUUID.toString() + ".dat");
		if (!Files.exists(playerFile)) {
			context.getSource().sendError(Text.literal("No inventory data found for player " + playerName)
					.setStyle(Style.EMPTY.withColor(Formatting.RED)));
			return 0;
		}

		try {
			MinecraftServer server = context.getSource().getServer();
			ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerUUID);

			if (player == null) {
				context.getSource().sendError(Text.literal("Player must be online to restore inventory.")
						.setStyle(Style.EMPTY.withColor(Formatting.RED)));
				return 0;
			}

			NbtCompound playerNBT;
			try (InputStream in = Files.newInputStream(playerFile)) {
				playerNBT = NbtIo.readCompressed(in, NbtSizeTracker.ofUnlimitedBytes());
			}

			double x = player.getX();
			double y = player.getY();
			double z = player.getZ();

			player.readNbt(playerNBT);

			player.refreshPositionAndAngles(x, y, z, player.getYaw(), player.getPitch());

			syncPlayerData(player);

			context.getSource().sendMessage(
					Text.literal("Player inventory restored successfully.").setStyle(Style.EMPTY.withColor(Formatting.GREEN)));
			return 1;

		} catch (IOException e) {
			LOGGER.error("Failed to restore player inventory", e);
			context.getSource().sendError(Text.literal("Failed to restore player inventory: " + e.getMessage())
					.setStyle(Style.EMPTY.withColor(Formatting.RED)));
			return 0;
		}
	}

	// Section: Utility Methods
	private void copyDirectory(Path source, Path target) throws IOException {
		Files.walk(source)
				.filter(path -> {
					return !path.toString().contains("logs") &&
							!path.toString().contains("tmp") &&
							!path.getFileName().toString().equals("session.lock");
				})
				.filter(path -> {
					int maxDepth = 3;
					int maxLength = 200;
					return source.relativize(path).getNameCount() <= maxDepth && path.toString().length() <= maxLength;
				})
				.forEach(path -> {
					try {
						Path targetPath = target.resolve(source.relativize(path));
						if (Files.isDirectory(path)) {
							Files.createDirectories(targetPath);
						} else {
							Files.copy(path, targetPath, StandardCopyOption.REPLACE_EXISTING);
						}
					} catch (IOException e) {
						throw new RuntimeException("Failed to copy file: " + path, e);
					}
				});
	}

	private void deleteDirectory(Path path) throws IOException {
		Files.walk(path)
				.sorted((a, b) -> b.compareTo(a))
				.forEach(p -> {
					try {
						Files.delete(p);
					} catch (IOException e) {
						throw new RuntimeException("Failed to delete " + p, e);
					}
				});
	}

	private String generateRandomSaveId() {
		Random rand = new Random();
		int saveId = rand.nextInt(90000) + 10000;
		return Integer.toString(saveId);
	}

	private UUID getPlayerUUID(String playerName, ServerCommandSource source) {
		MinecraftServer server = source.getServer();
		return server.getUserCache().findByName(playerName).map(gameProfile -> gameProfile.getId()).orElse(null);
	}

	private void syncPlayerData(ServerPlayerEntity player) {
		player.networkHandler.sendPacket(new HealthUpdateS2CPacket(
				player.getHealth(),
				player.getHungerManager().getFoodLevel(),
				player.getHungerManager().getSaturationLevel()));

		List<Pair<EquipmentSlot, ItemStack>> equipmentList = new ArrayList<>();
		for (EquipmentSlot slot : EquipmentSlot.values()) {
			ItemStack stack = player.getEquippedStack(slot);
			if (!stack.isEmpty()) {
				equipmentList.add(new Pair<>(slot, stack));
			}
		}
		player.networkHandler.sendPacket(new EntityEquipmentUpdateS2CPacket(player.getId(), equipmentList));

	}

	// Section: Tick Event
	private void registerTickEvent() {
		ServerTickEvents.START_SERVER_TICK.register(server -> {
			ticksSinceLastBackup++;
			if (ticksSinceLastBackup >= backupDelayMinutes * TICKS_PER_MINUTE) {
				LOGGER.info("Backup interval has passed. Starting automated world backup...");
				try {
					backupWorld(server, "auto");
					LOGGER.info("Automatic backup completed successfully.");
				} catch (IOException e) {
					LOGGER.error("Automatic backup failed", e);
				}
				ticksSinceLastBackup = 0;
			}
		});
	}
}
