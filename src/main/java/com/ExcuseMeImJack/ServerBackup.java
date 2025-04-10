package com.ExcuseMeImJack;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.network.packet.s2c.play.EntityEquipmentUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.HealthUpdateS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.Formatting;
import net.minecraft.util.WorldSavePath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.datafixers.util.Pair;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.*;

public class ServerBackup implements ModInitializer {
	public static final String MOD_ID = "ServerBackup";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static final int TICKS_PER_MINUTE = 1200;
	private int ticksSinceLastBackup = 0;
	private int backupDelayMinutes = 10;
	private static final String BACKUP_HISTORY_FILE = "backup_history.json";
	private Map<String, Path> backupHistory = new HashMap<>();

	@Override
	public void onInitialize() {

		loadBackupHistory();

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			registerBackupCommand(dispatcher);
			registerAutoDelayCommand(dispatcher);
			registerRestoreCommand(dispatcher);
			registerRestorePlayerCommand(dispatcher);
			registerListBackupsCommand(dispatcher);
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			ticksSinceLastBackup++;
			if (ticksSinceLastBackup >= TICKS_PER_MINUTE * backupDelayMinutes) {
				LOGGER.info("Backup interval has passed. Starting automated world backup...");
				try {
					backupWorld(server);
				} catch (IOException e) {
					LOGGER.error("Automated backup failed", e);
				}
				ticksSinceLastBackup = 0;
			}
		});
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
		File backupFolder = new File("server_backups");

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

		if (!backupFolder.exists() || !backupFolder.isDirectory()) {
			LOGGER.error("Backup folder does not exist or is not a directory.");
			return;
		}

		try (FileReader reader = new FileReader(backupHistoryFile)) {
			if (reader.ready()) {
				Type type = new TypeToken<Map<String, String>>() {
				}.getType();
				Map<String, String> backupHistoryAsStrings = gson.fromJson(reader, type);
				backupHistory.clear();
				for (Map.Entry<String, String> entry : backupHistoryAsStrings.entrySet()) {
					Path backupPath = Paths.get(entry.getValue());
					if (Files.exists(backupPath) && Files.isDirectory(backupPath)) {
						backupHistory.put(entry.getKey(), backupPath); // Keep valid backups
					} else {
						LOGGER.info("Backup directory no longer exists: " + backupPath);
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

		File[] backupDirs = backupFolder.listFiles((dir, name) -> new File(dir, name).isDirectory());
		if (backupDirs != null) {
			for (File backupDir : backupDirs) {
				String dirName = backupDir.getName();
				Path backupPath = backupDir.toPath();
				if (!backupHistory.containsKey(dirName)) {
					LOGGER.info("Adding new backup directory to history: " + backupPath);
					backupHistory.put(dirName, backupPath);
				}
			}
		} else {
			LOGGER.error("Error accessing the backup folder.");
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
						.then(CommandManager.argument("saveid", StringArgumentType.string())
								.executes(this::restoreWorld))));
	}

	private void registerRestorePlayerCommand(CommandDispatcher<ServerCommandSource> dispatcher) {
		dispatcher.register(CommandManager.literal("serverbackup")
				.then(CommandManager.literal("restoreplayer")
						.then(CommandManager.argument("saveid", StringArgumentType.string())
								.then(CommandManager.argument("player", StringArgumentType.string())
										.executes(this::restorePlayerInventory)))));
	}

	private void registerListBackupsCommand(CommandDispatcher<ServerCommandSource> dispatcher) {
		dispatcher.register(CommandManager.literal("serverbackup")
				.then(CommandManager.literal("list")
						.executes(this::listBackups)));
	}

	private int runBackupCommand(CommandContext<ServerCommandSource> context) {
		MinecraftServer server = context.getSource().getServer();
		context.getSource()
				.sendMessage(Text.literal("Backing up world...").setStyle(Style.EMPTY.withColor(Formatting.AQUA)));

		try {
			backupWorld(server);
			context.getSource().sendMessage(
					Text.literal("World backup completed successfully.").setStyle(Style.EMPTY.withColor(Formatting.GREEN)));
		} catch (IOException e) {
			LOGGER.error("Backup failed", e);
			context.getSource()
					.sendError(Text.literal("Backup failed: " + e.getMessage()).setStyle(Style.EMPTY.withColor(Formatting.RED)));
		}
		return 1;
	}

	private int setBackupDelay(CommandContext<ServerCommandSource> context) {
		int delay = IntegerArgumentType.getInteger(context, "time");
		this.backupDelayMinutes = delay;
		context.getSource().sendMessage(Text.literal("Automatic backup delay set to " + delay + " minutes.")
				.setStyle(Style.EMPTY.withColor(Formatting.AQUA)));
		return 1;
	}

	private int restoreWorld(CommandContext<ServerCommandSource> context) {
		String saveId = StringArgumentType.getString(context, "saveid");
		MinecraftServer server = context.getSource().getServer();

		// Check if the backup exists
		Path backupPath = backupHistory.get(saveId);
		if (backupPath == null) {
			context.getSource().sendError(
					Text.literal("No backup found with ID " + saveId).setStyle(Style.EMPTY.withColor(Formatting.YELLOW)));
			return 0;
		}

		// Get the timestamp and send a message to the user
		String timestamp = backupPath.getFileName().toString().split("_")[0];
		String formattedDate = formatTimestamp(timestamp);
		context.getSource()
				.sendMessage(Text.literal("Restoring world from backup " + saveId + " (Timestamp: " + formattedDate + ")")
						.setStyle(Style.EMPTY.withColor(Formatting.AQUA)));

		// Step 1: Kick all players
		for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
			player.sendMessage(
					Text.literal(
							"The world is being restored to the backup from " + formattedDate + ". You will be disconnected.")
							.setStyle(Style.EMPTY.withColor(Formatting.YELLOW)),
					false);
			player.networkHandler.disconnect(Text.literal("The world is being restored. Please reconnect shortly.")
					.setStyle(Style.EMPTY.withColor(Formatting.YELLOW)));
		}

		// Optional: Wait for players to disconnect (not the best solution but works for
		// now)
		try {
			Thread.sleep(5000); // Wait for a few seconds to ensure players disconnect
		} catch (InterruptedException e) {
			LOGGER.error("Error waiting for players to disconnect", e);
		}

		// Step 2: Restore the world from the backup
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

		// Step 3: Restart the server to load the restored world
		restartServer(server);

		return 1;
	}

	private void restartServer(MinecraftServer server) {
		// This method will trigger the server to restart after the world is restored
		LOGGER.info("Restarting the server...");

		// Send a restart message to all players
		server.getPlayerManager().broadcast(Text.literal("Server is restarting..."), false);

		// Stop the server, which will trigger a restart on most environments
		server.stop(false);
	}

	private int restorePlayerInventory(CommandContext<ServerCommandSource> context) {
		String saveId = StringArgumentType.getString(context, "saveid");
		String playerName = StringArgumentType.getString(context, "player");

		Path backupPath = backupHistory.get(saveId);
		if (backupPath == null) {
			context.getSource().sendError(
					Text.literal("No backup found with ID " + saveId).setStyle(Style.EMPTY.withColor(Formatting.YELLOW)));
			return 0;
		}

		context.getSource()
				.sendMessage(Text.literal("Restoring inventory for player " + playerName + " from backup " + saveId)
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

	private UUID getPlayerUUID(String playerName, ServerCommandSource source) {
		MinecraftServer server = source.getServer();
		return server.getUserCache().findByName(playerName).map(gameProfile -> gameProfile.getId()).orElse(null);
	}

	private void backupWorld(MinecraftServer server) throws IOException {
		Path serverDir = server.getRunDirectory().toAbsolutePath();

		Path backupsDir = serverDir.resolve("server_backups");

		Files.createDirectories(backupsDir);

		String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());

		String saveId = generateRandomSaveId();

		Path backupDest = backupsDir.resolve(timestamp + "_" + saveId);

		Path worldDir = server.getSavePath(WorldSavePath.ROOT);

		Path tempBackupDir = Files.createTempDirectory("world_backup_");

		LOGGER.info("Temporary backup location: " + tempBackupDir);

		try {
			LOGGER.info("World backup started.");

			copyDirectory(worldDir, tempBackupDir);
			LOGGER.info("Temporary backup completed.");

			Files.move(tempBackupDir, backupDest, StandardCopyOption.REPLACE_EXISTING);
			LOGGER.info("Backup successfully moved to: " + backupDest);

			backupHistory.put(saveId, backupDest);
		} catch (IOException e) {
			LOGGER.error("Backup failed", e);
			throw e;
		} finally {
			if (Files.exists(tempBackupDir)) {
				deleteDirectory(tempBackupDir);
			}
		}

		LOGGER.info("World backup completed.");
		saveBackupHistory();

		limitBackups(backupsDir, 10);
	}

	private String generateRandomSaveId() {
		Random rand = new Random();
		int saveId = rand.nextInt(90000) + 10000;
		return Integer.toString(saveId);
	}

	private int listBackups(CommandContext<ServerCommandSource> context) {
		// New method for loading the backup history directly in the list function
		Map<String, Path> backupHistory = new HashMap<>();
		Gson gson = new Gson();
		File backupHistoryFile = new File(BACKUP_HISTORY_FILE);
		File backupFolder = new File("server_backups");

		// Ensure backup history file exists or create it
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

		// Check if backup folder exists and is a valid directory
		if (!backupFolder.exists() || !backupFolder.isDirectory()) {
			LOGGER.error("Backup folder does not exist or is not a directory.");
			context.getSource().sendMessage(Text.literal("Backup folder is missing or invalid.")
					.setStyle(Style.EMPTY.withColor(Formatting.RED)));
			return 1;
		}

		// Read the backup history file
		try (FileReader reader = new FileReader(backupHistoryFile)) {
			if (reader.ready()) {
				Type type = new TypeToken<Map<String, String>>() {
				}.getType();
				Map<String, String> backupHistoryAsStrings = gson.fromJson(reader, type);
				for (Map.Entry<String, String> entry : backupHistoryAsStrings.entrySet()) {
					Path backupPath = Paths.get(entry.getValue());
					if (Files.exists(backupPath) && Files.isDirectory(backupPath)) {
						backupHistory.put(entry.getKey(), backupPath); // Add valid backups to the map
					} else {
						LOGGER.info("Backup directory no longer exists: " + backupPath);
					}
				}
			} else {
				LOGGER.info("Backup history file is empty.");
			}
		} catch (JsonSyntaxException e) {
			LOGGER.error("Invalid JSON format in backup history file. Reinitializing with an empty history.", e);
			backupHistory.clear();
			resetBackupHistoryFile();
		} catch (IOException e) {
			LOGGER.error("Error reading the backup history file.", e);
			backupHistory.clear();
		}

		// Add any new valid directories from the backup folder to the history
		File[] backupDirs = backupFolder.listFiles((dir, name) -> new File(dir, name).isDirectory());
		if (backupDirs != null) {
			for (File backupDir : backupDirs) {
				boolean alreadyExists = backupHistory.values().stream()
						.anyMatch(existingPath -> existingPath.getFileName().toString().equals(backupDir.getName()));

				if (!alreadyExists) {
					LOGGER.info("Adding new backup directory to history: " + backupDir.getPath());
					String newId = UUID.randomUUID().toString();
					backupHistory.put(newId, backupDir.toPath());
				}
			}
		} else {
			LOGGER.error("Error accessing the backup folder.");
			context.getSource().sendMessage(Text.literal("Error accessing the backup folder.")
					.setStyle(Style.EMPTY.withColor(Formatting.YELLOW)));
		}

		// Sort and display the backup list
		if (backupHistory.isEmpty()) {
			context.getSource().sendMessage(Text.literal("No backups found.")
					.setStyle(Style.EMPTY.withColor(Formatting.YELLOW)));
			return 1;
		}

		// Sort backups by timestamp (from the directory name)
		List<Map.Entry<String, Path>> sortedBackups = new ArrayList<>(backupHistory.entrySet());
		sortedBackups.sort((entry1, entry2) -> {
			String timestamp1 = entry1.getValue().getFileName().toString().split("_")[0] + '_'
					+ entry1.getValue().getFileName().toString().split("_")[1];
			String formattedDate1 = formatTimestamp(timestamp1);

			String timestamp2 = entry2.getValue().getFileName().toString().split("_")[0] + '_'
					+ entry2.getValue().getFileName().toString().split("_")[1];
			String formattedDate2 = formatTimestamp(timestamp2);

			return formattedDate1.compareTo(formattedDate2);
		});
		Text listBuilder = Text.literal(""); // Start with an empty string
		for (Map.Entry<String, Path> entry : sortedBackups) { // Iterate directly over the list of Map.Entry
			String saveId = entry.getKey(); // Get the saveId from the entry
			Path backupPath = entry.getValue(); // Get the backup path from the entry

			String timestamp = backupPath.getFileName().toString().split("_")[0] + '_' +
					backupPath.getFileName().toString().split("_")[1];
			String formattedDate = formatTimestamp(timestamp);

			// Create the backup entry with color formatting
			Text backupEntry = Text.literal("Backup ID: ")
					.setStyle(Style.EMPTY.withColor(TextColor.fromRgb(0xFFFFFF)))
					.append(Text.literal(saveId).setStyle(Style.EMPTY.withColor(TextColor.fromRgb(0x00FFFF))))
					.append(Text.literal(" | Timestamp: ").setStyle(Style.EMPTY.withColor(TextColor.fromRgb(0xFFFFFF))))
					.append(Text.literal(formattedDate).setStyle(Style.EMPTY.withColor(TextColor.fromRgb(0x00FF00))))
					.append(Text.literal("\n"));

			// Append the current backup entry to the main list builder
			listBuilder = listBuilder.copy().append(backupEntry);
		}

		// Send the final message to the player
		context.getSource().sendMessage(listBuilder);
		return 1;

	}

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

	private void restoreBackup(MinecraftServer server, Path backupPath) throws IOException {
		Path worldDir = server.getSavePath(WorldSavePath.ROOT);
		deleteDirectory(worldDir);
		copyDirectory(backupPath, worldDir);
	}

	private void limitBackups(Path backupsDir, int maxBackups) throws IOException {
		if (Files.list(backupsDir).count() > maxBackups) {
			List<Path> backups = new ArrayList<>();
			Files.list(backupsDir).forEach(backups::add);
			backups.sort(Comparator.comparing(path -> {
				try {
					return Files.getLastModifiedTime(path);
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			}));
			Path oldestBackup = backups.get(0);
			deleteDirectory(oldestBackup);
			LOGGER.info("Deleted old backup: " + oldestBackup.getFileName());
		}
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

	private String formatTimestamp(String timestamp) {
		try {
			String formattedTimestamp = timestamp;

			if (timestamp.length() <= 10) {
				formattedTimestamp += "_00-00-00";
			}

			SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");
			Date parsedDate = dateFormat.parse(formattedTimestamp);

			SimpleDateFormat readableDateFormat = new SimpleDateFormat("MMM dd, yyyy HH:mm:ss");
			return readableDateFormat.format(parsedDate);
		} catch (Exception e) {
			LOGGER.error("Failed to parse timestamp: " + timestamp, e);
			return "Invalid timestamp";
		}
	}
}
