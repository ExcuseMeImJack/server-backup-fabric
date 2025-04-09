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
import net.minecraft.text.Text;
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

	private static final int TICKS_PER_MINUTE = 1200; // 20 ticks per second * 60 seconds
	private int ticksSinceLastBackup = 0;
	private int backupDelayMinutes = 10; // Default automatic backup interval in minutes
	private static final String BACKUP_HISTORY_FILE = "backup_history.json"; // The file where history will be stored
	private Map<String, Path> backupHistory = new HashMap<>(); // To store backups and their IDs

	@Override
	public void onInitialize() {

		// Load backup history when the server starts
		loadBackupHistory();

		// Register the backup commands
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			registerBackupCommand(dispatcher);
			registerAutoDelayCommand(dispatcher);
			registerRestoreCommand(dispatcher);
			registerRestorePlayerCommand(dispatcher);
			registerListBackupsCommand(dispatcher);
		});

		// Register the periodic backup task
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			ticksSinceLastBackup++;
			if (ticksSinceLastBackup >= TICKS_PER_MINUTE * backupDelayMinutes) {
				LOGGER.info("Backup interval has passed. Starting automated world backup...");
				try {
					backupWorld(server);
				} catch (IOException e) {
					LOGGER.error("Automated backup failed", e);
				}
				ticksSinceLastBackup = 0; // Reset the backup counter
			}
		});
	}

	// Save the backup history to a file (JSON format)
	private void saveBackupHistory() {
		Gson gson = new Gson();
		Map<String, String> backupHistoryAsStrings = new HashMap<>();

		// Convert each Path to a String
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

		// Check if the file exists, if not, create a new one
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
			// Check if the file is empty before proceeding
			if (reader.ready()) {
				Type type = new TypeToken<Map<String, String>>() {
				}.getType();
				Map<String, String> backupHistoryAsStrings = gson.fromJson(reader, type);

				// Convert each string back to a Path
				backupHistory.clear();
				for (Map.Entry<String, String> entry : backupHistoryAsStrings.entrySet()) {
					backupHistory.put(entry.getKey(), Paths.get(entry.getValue()));
				}
			} else {
				LOGGER.info("Backup history file is empty. Initializing with an empty history.");
				backupHistory.clear(); // Handle the empty file case
			}
		} catch (JsonSyntaxException e) {
			LOGGER.error("Invalid JSON format in backup history file. Reinitializing with an empty history.", e);
			backupHistory.clear(); // Handle malformed JSON
			resetBackupHistoryFile(); // Optionally reset the file with valid JSON
		} catch (IOException e) {
			LOGGER.error("Error reading the backup history file.", e);
			backupHistory.clear(); // Handle general I/O errors
		}
	}

	private void resetBackupHistoryFile() {
		Gson gson = new Gson();
		try (FileWriter writer = new FileWriter(BACKUP_HISTORY_FILE)) {
			// Create an empty history map
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
		context.getSource().sendMessage(Text.of("Backing up world..."));

	try {
		backupWorld(server);
		context.getSource().sendMessage(Text.of("World backup completed successfully."));
	} catch (IOException e) {
		LOGGER.error("Backup failed", e);
		context.getSource().sendError(Text.of("Backup failed: " + e.getMessage()));
	}
	return 1;
}

private int setBackupDelay(CommandContext<ServerCommandSource> context) {
	int delay = IntegerArgumentType.getInteger(context, "time");
	this.backupDelayMinutes = delay;
	context.getSource().sendMessage(Text.of("Automatic backup delay set to " + delay + " minutes."));
	return 1;
}

private int restoreWorld(CommandContext<ServerCommandSource> context) {
	String saveId = StringArgumentType.getString(context, "saveid");
	MinecraftServer server = context.getSource().getServer();

	Path backupPath = backupHistory.get(saveId);
	if (backupPath == null) {
		context.getSource().sendError(Text.of("No backup found with ID " + saveId));
		return 0;
	}

	context.getSource().sendMessage(Text.of("Restoring world from backup " + saveId));
	try {
		restoreBackup(server, backupPath);
		context.getSource().sendMessage(Text.of("World restored successfully."));
	} catch (IOException e) {
		LOGGER.error("Failed to restore world", e);
		context.getSource().sendError(Text.of("Failed to restore world: " + e.getMessage()));
	}
	return 1;
}

private int restorePlayerInventory(CommandContext<ServerCommandSource> context) {
	String saveId = StringArgumentType.getString(context, "saveid");
	String playerName = StringArgumentType.getString(context, "player");

	Path backupPath = backupHistory.get(saveId);
	if (backupPath == null) {
		context.getSource().sendError(Text.of("No backup found with ID " + saveId));
		return 0;
	}

	context.getSource()
			.sendMessage(Text.of("Restoring inventory for player " + playerName + " from backup " + saveId));

	UUID playerUUID = getPlayerUUID(playerName, context.getSource());
	if (playerUUID == null) {
		context.getSource().sendError(Text.of("Player not found: " + playerName));
		return 0;
	}

	Path playerFile = backupPath.resolve("playerdata").resolve(playerUUID.toString() + ".dat");
	if (!Files.exists(playerFile)) {
		context.getSource().sendError(Text.of("No inventory data found for player " + playerName));
		return 0;
	}

	try {
		MinecraftServer server = context.getSource().getServer();
		ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerUUID);

		if (player == null) {
			context.getSource().sendError(Text.of("Player must be online to restore inventory."));
			return 0;
		}

		// Read NBT from backup .dat file
		NbtCompound playerNBT;
		try (InputStream in = Files.newInputStream(playerFile)) {
			playerNBT = NbtIo.readCompressed(in, NbtSizeTracker.ofUnlimitedBytes());
		}

			// Save current position
			double x = player.getX();
			double y = player.getY();
			double z = player.getZ();

			// Restore from NBT
			player.readNbt(playerNBT);

			// Re-apply original position (this keeps them in place!)
			player.refreshPositionAndAngles(x, y, z, player.getYaw(), player.getPitch());

			// Re-sync client view of health/hunger/equipment
			syncPlayerData(player);

			player.sendMessage(Text.of("Your inventory has been restored."), false);
			context.getSource().sendMessage(Text.of("Player inventory restored successfully."));
		return 1;

	} catch (IOException e) {
		LOGGER.error("Failed to restore player inventory", e);
		context.getSource().sendError(Text.of("Failed to restore player inventory: " + e.getMessage()));
		return 0;
	}
}

// Helper method to sync player data (health, food, etc.)
private void syncPlayerData(ServerPlayerEntity player) {
	// Sync health and hunger
	player.networkHandler.sendPacket(new HealthUpdateS2CPacket(
			player.getHealth(),
			player.getHungerManager().getFoodLevel(),
			player.getHungerManager().getSaturationLevel()));

	// Sync armor and held items
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
	MinecraftServer server = source.getServer(); // Get the server from the command source
	return server.getUserCache().findByName(playerName).map(gameProfile -> gameProfile.getId()).orElse(null);
	}

	private void backupWorld(MinecraftServer server) throws IOException {
		// Get the main server directory as File, then convert it to Path using
		// toAbsolutePath()
		Path serverDir = server.getRunDirectory().toAbsolutePath(); // Convert to Path and use toAbsolutePath()

		// Set backups directory in the main server directory
		Path backupsDir = serverDir.resolve("server_backups");

		// Ensure the backup directory exists
		Files.createDirectories(backupsDir);

		// Generate a timestamp for the backup filename
		String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());

		// Generate a random saveId (you could use UUID if you prefer more randomness)
		String saveId = generateRandomSaveId();

		// Combine timestamp with saveId to create a unique backup path
		Path backupDest = backupsDir.resolve(timestamp + "_" + saveId); // Backup destination with timestamp and saveId

		// Path to the world directory
		Path worldDir = server.getSavePath(WorldSavePath.ROOT);

		// Create a temporary directory for the backup
		Path tempBackupDir = Files.createTempDirectory("world_backup_");

		LOGGER.info("Temporary backup location: " + tempBackupDir);

		try {
			LOGGER.info("World backup started.");

			// Copy the world directory to the temporary backup directory
			copyDirectory(worldDir, tempBackupDir);
			LOGGER.info("Temporary backup completed.");

			// Move the temporary backup to the final destination
			Files.move(tempBackupDir, backupDest, StandardCopyOption.REPLACE_EXISTING);
			LOGGER.info("Backup successfully moved to: " + backupDest);

			// Store backup in history with saveId as the key
			backupHistory.put(saveId, backupDest);
		} catch (IOException e) {
			LOGGER.error("Backup failed", e);
			throw e;
		} finally {
			// Clean up temporary directory
			if (Files.exists(tempBackupDir)) {
				deleteDirectory(tempBackupDir);
			}
		}

		LOGGER.info("World backup completed.");
		saveBackupHistory();

		// Limit the number of backups (e.g., keep only the 10 most recent backups)
		limitBackups(backupsDir, 10);
	}

	private String generateRandomSaveId() {
		// Generate a random 5-digit number as the save ID
		Random rand = new Random();
		int saveId = rand.nextInt(90000) + 10000; // Ensures it's a 5-digit number
		return Integer.toString(saveId);
	}

	private int listBackups(CommandContext<ServerCommandSource> context) {
		if (backupHistory.isEmpty()) {
			context.getSource().sendMessage(Text.of("No backups found."));
			return 1;
		}

		StringBuilder listBuilder = new StringBuilder();
		for (Map.Entry<String, Path> entry : backupHistory.entrySet()) {
			String saveId = entry.getKey();
			Path backupPath = entry.getValue();

			// Extract the timestamp from the backup directory name (before the saveId)
			String[] parts = backupPath.getFileName().toString().split("_");
			String timestamp = parts.length > 0 ? parts[0] : "Unknown timestamp";

			// Format the timestamp to a human-readable date
			String formattedDate = formatTimestamp(timestamp);

			// Append the saveId, formatted timestamp, and the full backup path to the list
			listBuilder.append("Backup ID: ").append(saveId)
					.append(" | Timestamp: ").append(formattedDate)
					.append("\n");
		}

		// Send the backup list to the user
		context.getSource().sendMessage(Text.of(listBuilder.toString()));

		return 1;
	}

	private void copyDirectory(Path source, Path target) throws IOException {
		Files.walk(source)
				.filter(path -> {
					// Exclude "logs" and "tmp" folders, as well as the "session.lock" file
					return !path.toString().contains("logs") &&
							!path.toString().contains("tmp") &&
							!path.getFileName().toString().equals("session.lock");
				})
				.filter(path -> {
					// Additional checks for maximum depth and path length
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
		// Logic to restore the world from the backup
		Path worldDir = server.getSavePath(WorldSavePath.ROOT);
		deleteDirectory(worldDir);
		copyDirectory(backupPath, worldDir);
	}

	private void limitBackups(Path backupsDir, int maxBackups) throws IOException {
		// If there are too many backups, delete the oldest one
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

	// Format timestamp to human-readable date
	private String formatTimestamp(String timestamp) {
		try {
			// Define the format of the timestamp (example: "yyyy-MM-dd_HH-mm-ss")
			SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");
			Date parsedDate = dateFormat.parse(timestamp);

			// Format the date to a more readable format
			SimpleDateFormat readableDateFormat = new SimpleDateFormat("MMM dd, yyyy HH:mm:ss");
			return readableDateFormat.format(parsedDate);
		} catch (Exception e) {
			LOGGER.error("Failed to parse timestamp: " + timestamp, e);
			return "Invalid timestamp";
		}
	}
}
