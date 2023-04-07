package me.NoChance.PvPManager.Managers;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.jetbrains.annotations.NotNull;

import me.NoChance.PvPManager.PvPManager;
import me.NoChance.PvPManager.PvPlayer;
import me.NoChance.PvPManager.Dependencies.Hook;
import me.NoChance.PvPManager.Dependencies.Interfaces.WorldGuardDependency;
import me.NoChance.PvPManager.Events.PlayerCombatLogEvent;
import me.NoChance.PvPManager.Player.ProtectionResult;
import me.NoChance.PvPManager.Settings.Settings;
import me.NoChance.PvPManager.Tasks.CleanKillersTask;
import me.NoChance.PvPManager.Tasks.PvPToggleFeeTask;
import me.NoChance.PvPManager.Tasks.TagTask;
import me.NoChance.PvPManager.Utils.CombatUtils;
import me.chancesd.sdutils.utils.Log;
import me.chancesd.pvpmanager.utils.ScheduleUtils;

public class PlayerHandler {

	private final HashMap<UUID, PvPlayer> players = new HashMap<>();
	private final ConfigManager configManager;
	private final DependencyManager dependencyManager;
	private final PvPManager plugin;
	private final TagTask tagTask;

	public PlayerHandler(final PvPManager plugin) {
		this.plugin = plugin;
		this.configManager = plugin.getConfigM();
		this.dependencyManager = plugin.getDependencyManager();
		this.tagTask = new TagTask(plugin.getDisplayManager());
		if (Settings.isKillAbuseEnabled()) {
			ScheduleUtils.runAsyncTimer(new CleanKillersTask(this), Settings.getKillAbuseTime(), Settings.getKillAbuseTime(), TimeUnit.SECONDS);
		}
		if (Settings.getPvPDisabledFee() != 0) {
			ScheduleUtils.runAsyncTimer(new PvPToggleFeeTask(this), 0, 1, TimeUnit.HOURS);
		}

		addOnlinePlayers();
	}

	public final ProtectionResult tryCancel(final Player damager, final Player defender) {
		final PvPlayer attacker = get(damager);
		final PvPlayer attacked = get(defender);

		if (attacker.hasOverride() || Settings.borderHoppingVulnerable() && canAttackHooks(attacker, attacked))
			return ProtectionResult.FAIL_OVERRIDE;
		if (!attacked.getCombatWorld().isCombatAllowed())
			return ProtectionResult.WORLD_PROTECTION;
		if (attacked.hasRespawnProtection() || attacker.hasRespawnProtection())
			return ProtectionResult.RESPAWN_PROTECTION.setAttackerCaused(attacker.hasRespawnProtection());
		if (attacked.isNewbie() || attacker.isNewbie()) {
			if (dependencyManager.shouldDisableProtection(damager, defender, ProtectionResult.NEWBIE))
				return ProtectionResult.FAIL_PLUGIN_HOOK;
			return ProtectionResult.NEWBIE.setAttackerCaused(attacker.isNewbie());
		}
		if (!attacker.hasPvPEnabled() || !attacked.hasPvPEnabled()) {
			if (dependencyManager.shouldDisableProtection(damager, defender, ProtectionResult.PVPDISABLED))
				return ProtectionResult.FAIL_PLUGIN_HOOK;
			return ProtectionResult.PVPDISABLED.setAttackerCaused(!attacker.hasPvPEnabled());
		}
		if (dependencyManager.shouldProtectAFK(defender))
			return ProtectionResult.AFK_PROTECTION;
		return ProtectionResult.FAIL;
	}

	/**
	 * Use this method to check PvP instead of using {@link PlayerHandler#tryCancel(Player, Player)}
	 * This method will not be changed while the previous might change at any time.
	 *
	 * @param attacker
	 *                 The attacking player
	 * @param defender
	 *                 The player being attacked
	 *
	 * @return true if the attack didn't get blocked or if it got override, otherwise false
	 */
	public final boolean canAttack(final Player attacker, final Player defender) {
		return tryCancel(attacker, defender).canAttack();
	}

	private boolean canAttackHooks(final PvPlayer attacker, final PvPlayer defender) {
		if (attacker.isInCombat() && defender.isInCombat())
			return dependencyManager.canAttack(attacker.getPlayer(), defender.getPlayer());
		return false;
	}

	/**
	 * @param player the player instance
	 * @return PvPlayer instance for the provided player
	 */
	@NotNull
	public final PvPlayer get(final Player player) {
		final PvPlayer pvPlayer = players.get(player.getUniqueId());
		return pvPlayer != null ? pvPlayer : addUser(new PvPlayer(player, plugin));
	}

	@NotNull
	private PvPlayer addUser(final PvPlayer p) {
		final boolean save = CombatUtils.isReal(p.getUUID());
		// Save only if player actually exists
		if (save) {
			players.put(p.getUUID(), p);
		}
		Log.debug("Adding " + p + " to online players. Saved: " + save);
		return p;
	}

	public final void removeUser(final PvPlayer player) {
		if (player.isInCombat()) {
			player.unTag();
		}
		player.cleanForRemoval();
		players.remove(player.getUUID());
	}

	public final void applyPunishments(final PvPlayer player) {
		final Player p = player.getPlayer();
		final PlayerCombatLogEvent event = new PlayerCombatLogEvent(p, player);
		Bukkit.getPluginManager().callEvent(event);
		if (Settings.isKillOnLogout()) {
			player.setPvpLogged(true);
			p.setHealth(0);
			player.setPvpLogged(false);
		}
		if (Settings.getFineAmount() != 0) {
			player.applyFine();
		}
	}

	public void handleCombatLogDrops(final PlayerDeathEvent event, final Player player) {
		if (!Settings.isDropExp()) {
			keepExp(event);
		}
		if (!Settings.isDropInventory() && Settings.isDropArmor()) {
			CombatUtils.fakeItemStackDrop(player, player.getInventory().getArmorContents());
			player.getInventory().setArmorContents(null);
		} else if (Settings.isDropInventory() && !Settings.isDropArmor()) {
			CombatUtils.fakeItemStackDrop(player, player.getInventory().getContents());
			player.getInventory().clear();
		}
		if (!Settings.isDropInventory() || !Settings.isDropArmor()) {
			keepInv(event);
		}
	}

	public void handlePlayerDrops(final PlayerDeathEvent event, final Player player, final Player killer) {
		switch (Settings.getDropMode()) {
		case DROP:
			if (killer == null) {
				keepInv(event);
				keepExp(event);
			}
			break;
		case KEEP:
			if (killer != null) {
				keepInv(event);
				keepExp(event);
			}
			break;
		case TRANSFER:
			if (killer != null) {
				final ItemStack[] drops = event.getDrops().toArray(new ItemStack[event.getDrops().size()]);
				final HashMap<Integer, ItemStack> returned = killer.getInventory().addItem(drops);
				CombatUtils.fakeItemStackDrop(player, returned.values().toArray(new ItemStack[returned.values().size()]));
				event.getDrops().clear();
			}
			break;
		case CLEAR:
			event.getDrops().clear();
			event.setDroppedExp(0);
			break;
		default:
			break;
		}
	}

	private void keepInv(final PlayerDeathEvent event) {
		event.setKeepInventory(true);
		event.getDrops().clear();
	}

	private void keepExp(final PlayerDeathEvent event) {
		event.setKeepLevel(true);
		event.setDroppedExp(0);
	}

	private void addOnlinePlayers() {
		PvPlayer.startExecutor();
		for (final Team team : Bukkit.getScoreboardManager().getMainScoreboard().getTeams()) {
			if (team.getName().startsWith("PVP-") && team.getName().length() == 16) {
				Log.debug("Unregistered leftover team: " + team.getName() + " Entries: " + team.getEntries());
				team.unregister();
			}
		}
		for (final Player p : plugin.getServer().getOnlinePlayers()) {
			get(p);
		}
	}

	public void handlePluginDisable() {
		tagTask.cancel();
		for (final PvPlayer p : new HashSet<>(players.values())) {
			removeUser(p);
		}
		removeTeams();
		Log.infoColor(ChatColor.RED + "Saving player data to storage...");
		PvPlayer.shutdownExecutorAndWait();
	}

	private void removeTeams() {
		final Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
		final Team pvpon = scoreboard.getTeam("PvPOn");
		if (pvpon != null) {
			pvpon.unregister();
		}
		final Team pvpoff = scoreboard.getTeam("PvPOff");
		if (pvpoff != null) {
			pvpoff.unregister();
		}
		final Objective health = scoreboard.getObjective("PvP_Health");
		if (health != null) {
			health.unregister();
		}
	}

	// TODO replace untag and tag with gettagtask
	public final void removeFromTagTask(final PvPlayer p) {
		tagTask.untag(p);
	}

	public final void addToTagTask(final PvPlayer p) {
		tagTask.addTagged(p);
	}

	public final Map<UUID, PvPlayer> getPlayers() {
		return players;
	}

	public final Set<PvPlayer> getPlayersInCombat() {
		return tagTask.getTaggedPlayers();
	}

	@NotNull
	public final PvPManager getPlugin() {
		return plugin;
	}

	public ConfigManager getConfigManager() {
		return configManager;
	}

}
