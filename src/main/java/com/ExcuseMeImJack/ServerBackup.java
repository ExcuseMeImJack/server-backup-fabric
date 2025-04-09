package com.ExcuseMeImJack;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.WorldSavePath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;

public class ServerBackup implements ModInitializer {
	public static final String MOD_ID = "ServerBackup";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

		private static final int TICKS_PER_MINUTE = 1200; // 20 ticks per second * 60 seconds
		private int ticksSinceLastBackup = 0;

		@Override
		public void onInitialize() {
			// Register the backup command
				CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
					registerBackupCommand(dispatcher);
				});

				// Register the periodic backup task
				ServerTickEvents.END_SERVER_TICK.register(server -> {
					ticksSinceLastBackup++;
					if (ticksSinceLastBackup >= TICKS_PER_MINUTE * 10) { // 10 minutes (10 * 60 seconds)
						// Perform backup
						LOGGER.info("10 minutes have passed. Starting automated world backup...");
						try {
							backupWorld(server);
						} catch (IOException e) {
							LOGGER.error("Automated backup failed", e);
						}
						// Reset the backup counter
						ticksSinceLastBackup = 0;
					}
				});
			}

		private void registerBackupCommand(CommandDispatcher<ServerCommandSource> dispatcher) {
			dispatcher.register(CommandManager.literal("serverbackup")
					.requires(source -> {
						if (!source.hasPermissionLevel(4)) {
							source.sendError(Text.of("You do not have permission to run this command."));
							return false;
						}
						return true;
					})
					.executes(this::runBackupCommand));
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
				} catch (Exception e) {
					LOGGER.error("Unexpected error occurred", e);
					context.getSource().sendError(Text.of("An unexpected error occurred trying to execute that command."));
				}
				return 1;
			}

		private void backupWorld(MinecraftServer server) throws IOException {
			Path worldDir = server.getSavePath(WorldSavePath.ROOT);
			Path backupsDir = worldDir.getParent().resolve("server_backups");

				Files.createDirectories(backupsDir);
				String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
				Path backupDest = backupsDir.resolve("backup_" + timestamp);

				Path tempBackupDir = Files.createTempDirectory("world_backup_");
				LOGGER.info("Temporary backup location: " + tempBackupDir);

				try {
					LOGGER.info("World backup started.");
					LOGGER.info("Backing up world from: " + worldDir);
					LOGGER.info("Backup will be saved to: " + tempBackupDir);

						copyDirectory(worldDir, tempBackupDir);

						LOGGER.info("Temporary backup completed.");

						Files.move(tempBackupDir, backupDest, StandardCopyOption.REPLACE_EXISTING);
						LOGGER.info("Backup successfully moved to: " + backupDest);
					} catch (IOException e) {
						LOGGER.error("Backup failed", e);
						throw e;
					} finally {
						if (Files.exists(tempBackupDir)) {
							deleteDirectory(tempBackupDir);
						}
					}

				LOGGER.info("World backup completed.");
				limitBackups(backupsDir, 10);
			}

		private void copyDirectory(Path source, Path target) throws IOException {
			Files.walk(source)
					.filter(path -> !path.toString().contains("logs") && !path.toString().contains("tmp"))
					.filter(path -> {
						int maxDepth = 3;
						int maxLength = 200;
						return source.relativize(path).getNameCount() <= maxDepth && path.toString().length() <= maxLength;
					})
					.forEach(path -> {
						try {
							if (source.relativize(path).toString().contains("backups")
									|| path.getFileName().toString().equals("session.lock")) {
								return;
							}

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

		private void limitBackups(Path backupsDir, int maxBackups) throws IOException {
			Files.list(backupsDir)
					.filter(Files::isDirectory)
					.sorted((a, b) -> {
						try {
							return Files.getLastModifiedTime(a).compareTo(Files.getLastModifiedTime(b));
						} catch (IOException e) {
							throw new RuntimeException(e);
						}
					})
					.skip(maxBackups)
					.forEach(path -> {
						try {
							deleteDirectory(path);
							LOGGER.info("Deleted old backup: " + path.getFileName());
						} catch (IOException e) {
							LOGGER.error("Failed to delete old backup: " + path.getFileName(), e);
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
}
