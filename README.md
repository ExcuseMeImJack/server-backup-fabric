# ServerBackupFabric
## Description
**ServerBackupFabric** is a Minecraft mod designed to automate and simplify the process of creating and managing backups for your Minecraft server. It allows server administrators to create manual and automatic backups of the world, restore backups, and even restore individual player inventories. The mod ensures that your server data is safe and easily recoverable in case of unexpected issues.

---
### Features
- **Manual backups**: Create backups of your server world on demand.
- **Automatic backups**: Schedule periodic backups to ensure your data is always protected.
- **Backup Management**: Automatically limit the number of backups to save disk space.
- **Restore World**: Restore the entire server world from a backup.
- **Restore Player Inventory**: Restore individual player inventories from backups.
- **Backup Suggestions**: Autocomplete suggestions for backup IDs and online players in commands.

---
### Installation
##### Requirements
- **Minecraft Version**: 1.21.5
- **Fabric Loader Version**: 0.16.10
- **Fabric API Version**: 0.119.5+1.21.5
##### Steps
1. **Download the Mod:**
   - Download the latest release of the mod from the [Releases](https://github.com/ExcuseMeImJack/server-backup-fabric/releases) section of this repository.
2. **Install Fabric Loader:**
   - Download and install the Fabric Loader from [fabricmc.net](https://fabricmc.net/use/installer/).
3. **Install Fabric API:**
   - Download the Fabric API (version `0.119.5+1.21.5`) from [CurseForge](https://www.curseforge.com/minecraft/mc-mods/fabric-api) and place it in your server's `mods` folder.
4. **Add the Mod:**
   - Place the `server-backup-fabric.jar `file into your server's `mods` folder.
5. **Start the Server:**
   - Start your Minecraft server. The mod will automatically initialize and create necessary files.

---
### Usage
##### Commands
The mod provides several commands to manage backups and restores. All commands require a permission level of 4 (server operator).

**1. Create a Manual Backup**
```
/serverbackup
```
- Creates a manual backup of the server world.

**2. Set Automatic Backup Delay**
```
/serverbackup autodelay <time>
```
- Sets the delay (in minutes) between automatic backups.
- Example:
  ```
  /serverbackup autodelay 30
  ```

**3. List Backups**
```
/serverbackup list
```
- Displays a list of all available backups, sorted by data and time.

**4. Restore the World**
```
/serverbackup restoreworld <backupID>
```
- Restores the server world from a specific backup.
  - Does not restore player inventory; only the world.
- Example:
  ```
  /serverbackup restoreworld 12345
  ```

**5. Restore a Player's Inventory**
```
/serverbackup restoreplayer <backupID> <playername>
```
- Restores a specific player's inventory from a backup.
- Example:
  ```
  /serverbackup restoreplayer 12345 ChickenJockey153
  ```

---
### Configuration
The mod automatically creates backups in the `server_backups` folder within your server directory. Backups are organized into:

- `manual_backups`: For manually created backups.
- `auto_backups`: For automatically created backups.

Backup Limits
- The mod limits the number of backups to **5** for both manual and automatic backups (**5 manual, 5 automatic. 10 total backups**). Older backups are automatically deleted to save disk space.

---
### Contributing
Contributions are welcome! If you encounter any issues or have feature requests, please open an issue or submit a pull request.

---
### License
This project is licensed under the CC0 1.0 License.

---
### Support
If you have any questions or need help, feel free to open an issue on the [GitHub repository](https://github.com/ExcuseMeImJack/server-backup-fabric/issues).

