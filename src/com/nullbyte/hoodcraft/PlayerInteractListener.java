package com.nullbyte.hoodcraft;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Random;
import java.util.Scanner;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Dispenser;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Powerable;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MainHand;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.tags.ItemTagType;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BlockIterator;
import org.bukkit.util.Vector;
import org.fusesource.jansi.Ansi.Color;

public class PlayerInteractListener extends JavaPlugin implements Listener {
	
	JavaPlugin plugin;
	
	private class SortByDistance implements Comparator<Entity> { 
		
		public Location playerHeadPos;
		
		public SortByDistance(Location playerHeadPos) {
			this.playerHeadPos = playerHeadPos;
		}
		
	    public int compare(Entity a, Entity b) { 
	    	if(playerHeadPos != null)
	    		return (int)(a.getLocation().clone().subtract(playerHeadPos).toVector().lengthSquared()-b.getLocation().clone().subtract(playerHeadPos).toVector().lengthSquared());
	        return 0;
	    } 
	} 
	
	private class BedrockBreaker {
		public Player player;
		public Block lastBlock;
		public int lastDamage;
		
		public BedrockBreaker(Player p) {
			player = p;
			lastDamage = 0;
		}
		
		public BedrockBreaker(Player p, Block b) {
			this(p);
			lastBlock = b;
		}
		
		public boolean isPlayer(Player p) {
			return player == p;
		}
		
		public boolean attackBlock(Block b) {
			if(!b.equals(lastBlock)) {
				lastBlock = b;
				lastDamage = 1;
				return false;
			}
			lastDamage++;
			if (lastDamage >= 15) return true;
			return false;
		}
		
	}
	

	
	private class DispenserTurret extends BukkitRunnable {
		public Dispenser dispenser;
		ArrayList<String> entityTargets = new ArrayList<String>();
		public Location location;
		public String owner;
		private String mode; // all, whitelist, blacklist
		private int statusCount;
		private boolean hasInfinity;
		private float range;
		private float arrowVelocity;
		private long fireRate;
		private long currentDelay;
		public static final double raycastPrecision = 0.05;
		
		public void setOwner(Entity e) {
			owner = e.getName();
		}
		
		public boolean isOwner(Entity e) {
			if(owner == null) return false;
			return owner.equals(e.getName());
		}
		
		public String getOwner() {
			return owner;
		}
		
		public DispenserTurret(Dispenser d) {
			dispenser = d;
			location = d.getLocation();
			owner = null;
			mode = "whitelist";
			statusCount = 1;
			hasInfinity = false;
			range = 5.0f;
			arrowVelocity = 0.4f;
			fireRate = 64;
			currentDelay = fireRate;
			Inventory inv = getInventory();
			if(loadTargets()) {
				Bukkit.broadcastMessage("Old turret restored");
			}
			else if(inv.contains(Material.WRITTEN_BOOK)) {
				int writtenBookIndex = inv.first(Material.WRITTEN_BOOK);
				ItemStack book = inv.getItem(writtenBookIndex);
				BookMeta meta = (BookMeta) book.getItemMeta();
				int pages = meta.getPageCount();
				for(int i=1; i<=pages; i++) {
					for(String s : meta.getPage(i).split("\n"))
						entityTargets.add(s.trim());
				}
				saveTargets();
			}
			updateModifiers(true);
		}
		
		public void saveTargets() {
			PersistentDataContainer pd = dispenser.getPersistentDataContainer();
			NamespacedKey key = new NamespacedKey(plugin, "targets");
			pd.set(key, PersistentDataType.STRING, String.join("\n", entityTargets));
			dispenser.update();
		}
		
		public boolean loadTargets() {
			PersistentDataContainer pd = dispenser.getPersistentDataContainer();
			NamespacedKey key = new NamespacedKey(plugin, "targets");
			if(pd.has(key, PersistentDataType.STRING)) {
				String targetString = pd.get(key, PersistentDataType.STRING);
				entityTargets = new ArrayList<String>();
				for(String target : targetString.split("\n"))
					entityTargets.add(target);
				return true;
			}
			
			return false;
		}
		
		public Inventory getInventory() {
			return dispenser.getInventory();
		}
		
		public boolean hitsBlock(Location startPos, Location targetPos) {
			if(startPos == null || targetPos == null) return true;
			Vector direction = targetPos.clone().subtract(startPos).toVector().normalize();
			BlockIterator blockIter = new BlockIterator(dispenser.getWorld(),startPos.toVector().clone(), direction, raycastPrecision, (int)Math.ceil(Math.abs(targetPos.clone().subtract(startPos).length())));
			Block currentBlock;
			while(blockIter.hasNext()) {
				currentBlock = blockIter.next();
				if(!currentBlock.isPassable()) return true;
				//if(!(currentBlock.getType().equals(Material.AIR) || currentBlock.getType().equals(Material.WATER))) return true;
			}
			return false;
		}
		
		public boolean hasInfinityItem() {
			Inventory inv = getInventory();
			int bow, crossbow;
			if(inv.contains(Material.BOW)) {
				bow = inv.first(Material.BOW);
				if (inv.getItem(bow).containsEnchantment(Enchantment.ARROW_INFINITE)) return true;
			}
			if(inv.contains(Material.CROSSBOW)) {
				crossbow = inv.first(Material.CROSSBOW);
				if(inv.getItem(crossbow).containsEnchantment(Enchantment.ARROW_INFINITE)) return true;
			}
			return false;
		}
		
		public void updateRange() {
			Inventory inv = getInventory();
			if(inv == null) return;
			if(!inv.contains(Material.DIAMOND_BLOCK)) {
				range =  5.0f;
				return;
			}
			range = 5.0f + (float)inv.getItem(inv.first(Material.DIAMOND_BLOCK)).getAmount()*0.921875f;
			if(range > 64f) range = 64.0f;
		}
		
		public void updateArrowVelocity() {
			Inventory inv = getInventory();
			if(inv == null) return;
			if(!inv.contains(Material.NETHER_STAR)) {
				arrowVelocity = 0.4f;
				return;
			}
			arrowVelocity = 0.4f + (float)inv.getItem(inv.first(Material.NETHER_STAR)).getAmount()*0.15f;
			if(arrowVelocity > 10f) arrowVelocity = 10f;
		}
		
		public boolean updateFireRate() {
			long oldFireRate = fireRate;
			Inventory inv = getInventory();
			if(inv == null) return false;
			if(!inv.contains(Material.EMERALD_BLOCK)) {
				fireRate =  64;
			}
			else {
				int emeraldBlock = inv.first(Material.EMERALD_BLOCK);
				fireRate = 64 - inv.getItem(emeraldBlock).getAmount();
			}
			
			if(oldFireRate != fireRate) {
				return true;
			}
			return false;
			
		}
		
		public boolean hasLineOfSight(Vector facing, Location startPos, Location endPos) {
			if(facing == null || startPos == null || endPos == null) return false;
			Vector r = endPos.clone().subtract(startPos).toVector().normalize();
			return r.dot(facing) > 0 && !hitsBlock(startPos, endPos);
		}
		
		public void doPushBack() {
			Collection<Entity> nearbyEnts = dispenser.getWorld().getNearbyEntities(dispenser.getLocation(), 3f, 3f, 3f);
			ArrayList<Entity> closest = new ArrayList<Entity>();
			for(Entity ent : nearbyEnts) {
				if(!(ent instanceof LivingEntity)) continue;
				if(!isOwner(ent) && Math.abs(ent.getLocation().distance(dispenser.getLocation().clone().add(new Vector(0.5,0.5,0.5)))) <= 1.5 ) {
					ent.setVelocity(ent.getLocation().clone().subtract(dispenser.getLocation().clone().add(new Vector(0.5,0.5,0.5))).toVector().normalize());
					ent.sendMessage(ChatColor.DARK_RED + getOwner() + "'s turret has pushed you back");
					}
				}
		}
		
		public Location getClosestTarget(Location startLoc, Vector facing) {
			Collection<Entity> nearbyEnts = dispenser.getWorld().getNearbyEntities(startLoc, range, range, range);
			
			ArrayList<Entity> closest = new ArrayList<Entity>();
			for(Entity ent : nearbyEnts) {
				if(!(ent instanceof LivingEntity)) continue;
				if(!hasLineOfSight(facing.clone(), startLoc.clone(), ent.getLocation().clone().add(new Vector(0,ent.getHeight()*0.1,0)))) {
					if(!hasLineOfSight(facing.clone(), startLoc.clone(), ent.getLocation().clone().add(new Vector(0,ent.getHeight()*0.5,0)))) {
						if(!hasLineOfSight(facing.clone(), startLoc.clone(), ent.getLocation().clone().add(new Vector(0,ent.getHeight()*0.75,0)))) {
							continue;
						}
					}
				}
				//if(hitsBlock(dispenser.getLocation().clone(), ent.getLocation().clone()) > dispenser.getLocation().clone().distance(ent.getLocation())) continue;
				if(mode.contentEquals("whitelist")) {
					if(!ent.isDead() && entityTargets.contains(ent.getName())) {
						if(!isOwner(ent)) closest.add(ent);
					}
				}
				else if(mode.equals("blacklist")) {
					if(!ent.isDead() && !entityTargets.contains(ent.getName())) {
						if(!isOwner(ent)) closest.add(ent);
					}
				}
				else if(mode.equals("all")) {
					if(!ent.isDead()) {
						if(!isOwner(ent)) closest.add(ent);
					}
				}
			}
				
			Collections.sort(closest, new SortByDistance(startLoc.clone()));
			if(closest.size() > 0) {
				//Bukkit.broadcastMessage("Turret targetting " + closest.get(0).getName());
				Entity closestEnt = closest.get(0);
				ArrayList<Location> targetLocs = new ArrayList<Location>();
				if(hasLineOfSight(facing.clone(), startLoc.clone(), closestEnt.getLocation().clone().add(new Vector(0,closestEnt.getHeight()*0.1,0)))) targetLocs.add(closestEnt.getLocation().clone().add(new Vector(0,closestEnt.getHeight()*(0.1+random.nextDouble()*0.1)-(random.nextDouble()*0.1),0)));
				if(hasLineOfSight(facing.clone(), startLoc.clone(), closestEnt.getLocation().clone().add(new Vector(0,closestEnt.getHeight()*0.5,0)))) targetLocs.add(closestEnt.getLocation().clone().add(new Vector(0,closestEnt.getHeight()*(0.5+random.nextDouble()*0.15)-(random.nextDouble()*0.15),0)));
				if(hasLineOfSight(facing.clone(), startLoc.clone(), closestEnt.getLocation().clone().add(new Vector(0,closestEnt.getHeight()*0.75,0)))) targetLocs.add(closestEnt.getLocation().clone().add(new Vector(0,closestEnt.getHeight()*(0.75+random.nextDouble()*0.25)-(random.nextDouble()*0.15),0)));
				if(targetLocs.size() > 0) return targetLocs.get(random.nextInt(targetLocs.size()));
			}
			return null;
		}
		
		public String getMode() {
			return mode;
		}
		
		public void setMode(String newMode) {
			mode = newMode;
		}
		
		public String toggleMode() {
			if(mode.equals("blacklist")) mode = "whitelist";
			else if(mode.equals("whitelist")) mode = "all";
			else if(mode.equals("all")) mode = "blacklist";
			return mode;
		}
		
		public boolean shootNearby() {
			double dx, dy, dz;
			dx = 0.5;
			dy = 0.5;
			dz = 0.5;
			Location arrowStart = new Location(dispenser.getWorld(), (double)dispenser.getX()+dx, (double)dispenser.getY()+dy, (double)dispenser.getZ()+dz);
			arrowStart.add(((Directional)dispenser.getBlockData()).getFacing().getDirection().multiply(0.6));
			Location closest = getClosestTarget(arrowStart.clone(), ((Directional)dispenser.getBlockData()).getFacing().getDirection().multiply(0.52));
			if(closest == null) return false;
			dispenser.getWorld().playSound(arrowStart, Sound.BLOCK_METAL_BREAK, 1.0f, 10f);
			Vector direction = closest.clone().subtract(arrowStart).toVector().normalize();
			dispenser.getWorld().spawnArrow(arrowStart, direction, arrowVelocity, 1f);
			return true;
		}
		
		public void updateModifiers(boolean firstUpdate) {
			boolean oldInfinity = hasInfinity;
			double oldRange = range;
			float oldArrowVelocity = arrowVelocity;
			hasInfinity = hasInfinityItem();
			updateRange();
			updateArrowVelocity();
			boolean fireRateChanged = updateFireRate();
			if(firstUpdate) return;
			if(oldInfinity != hasInfinity || oldRange != range || oldArrowVelocity != arrowVelocity || fireRateChanged) {
				Player p = Bukkit.getPlayer(getOwner());
				if(p != null) {
					p.sendMessage(ChatColor.WHITE + "Your turret has been modified");
					sendTurretInfo(p);
				}
			}
		}
		
		
		public void sendTurretInfo(Player p) {
			p.sendMessage(ChatColor.DARK_PURPLE + "Infinity Modifier: " + hasInfinity + ChatColor.DARK_GREEN + "\nDetection Range: " + range + ChatColor.YELLOW + "\nArrow Launch Velocity: " + arrowVelocity + ChatColor.DARK_RED +"\nFire Rate: " + fireRate);
		}

		@Override
		public void run() {
			if(statusCount % 5 == 0) updateModifiers(false);
			if(statusCount % 10 == 0)doPushBack();
			statusCount = (statusCount + 1)%64;
			Inventory inv = getInventory();
			if(!dispenser.getWorld().getBlockAt(location).getType().equals(Material.DISPENSER)) {
				turrets.remove(this);
				saveTurrets();
				this.cancel();
				return;
			}
			if(inv == null) this.cancel();
			if( currentDelay == 0 && (hasInfinity || inv.contains(Material.ARROW))) {
				int arrowIndex = inv.first(Material.ARROW);
				boolean shot = shootNearby();
				if(shot && !hasInfinity) {
					inv.setItem(arrowIndex, new ItemStack(Material.ARROW,inv.getItem(arrowIndex).getAmount()-1));
				}
				if(shot) currentDelay = fireRate+1;
			}
			
			currentDelay--;
			if(currentDelay < 0) currentDelay = 0;
			
		}
	}
	
	private class LightningStriker extends BukkitRunnable {

	    private final JavaPlugin plugin;
	    
	    private int counter;
	    private Location lookingAt;
	    private World world;

	    public LightningStriker(JavaPlugin plugin, int counter, Location lookingAt, World w) {
	        this.plugin = plugin;
	        this.counter = counter;
	        this.lookingAt = lookingAt;
	        this.world = w;
	    }

	    @Override
	    public void run() {
	        if (counter > 0) { 
	        	for(int i=0; i < 10; i++) {
					Location target = new Location(world, lookingAt.getX()-2+random.nextInt(5),lookingAt.getY(),lookingAt.getZ()-2+random.nextInt(5) );
					world.strikeLightning(target);
				}
	        	if(random.nextInt(3) == 0)counter++;
				counter--;
	        } 
	        else {
	            this.cancel();
	        }
	    }

	}
	
	private class SpellCaster {
		public Player caster;
		long lastCast;
		
		public SpellCaster(Player p) {
			caster = p;
			lastCast = new java.util.Date().getTime() - 5000;
		}
		
		public boolean cast() {
			long thisTime = new java.util.Date().getTime();
			if(thisTime - lastCast < 5000) {
				caster.sendMessage(ChatColor.DARK_RED + "Wait " + (5.0 - (thisTime-lastCast)/1000.0) + " seconds before casting again!");
				return false;
			}
			lastCast = thisTime;
			return true;
		}
	}
	
	private ArrayList<SpellCaster> spellCasters = new ArrayList<SpellCaster>();
	private ArrayList<DispenserTurret> turrets = new ArrayList<DispenserTurret>();
	
	public boolean addTurret(Block b) {
		for(DispenserTurret d : turrets) {
			if(d.dispenser.equals((Dispenser)b.getState())) return false;
		}
		DispenserTurret newTurret = new DispenserTurret((Dispenser)b.getState());
		turrets.add(newTurret);
		newTurret.runTaskTimer(this, 0, 0);
		return true;
	}
	
	
	public DispenserTurret getDispenserTurret(Block b) {
		for (DispenserTurret t : turrets)
			if(t.dispenser.equals((Dispenser)b.getState())) return t;
		return null;
	}
	
	public boolean castSpell(Player p) {
		for(SpellCaster caster : spellCasters) {
			if(caster.caster.equals(p)) return caster.cast();
		}
		SpellCaster newCaster = new SpellCaster(p);
		spellCasters.add(newCaster);
		return newCaster.cast();
	}
	
	private ArrayList<BedrockBreaker> bedrockBreakers = new ArrayList<BedrockBreaker>();
	
	private boolean attackBlock(Player p, Block b) {
		for (BedrockBreaker breaker : bedrockBreakers) {
			if(breaker.isPlayer(p)) {
				return breaker.attackBlock(b);
			}
		}
		BedrockBreaker breaker = new BedrockBreaker(p, b);
		bedrockBreakers.add(breaker);
		return breaker.attackBlock(b);
	}
	
	private static ArrayList<String> bedrockPlayers = new ArrayList<String>();
	private Random random = new Random();
	@Override
	public void onEnable() {
	Bukkit.getServer().getPluginManager().registerEvents(this, this);
	this.plugin = this;
	
	String fileSeparator = System.getProperty("file.separator");
	File pluginDir = new File("plugins" + fileSeparator + "HoodCraft");
	pluginDir.mkdir();
	loadTurrets();
	}
	
	
	public Entity rayTrace(Location headPos, Location lookPos, World w, Vector dir) {
		Vector r = null;
		if(lookPos != null)
			r = lookPos.clone().subtract(headPos).toVector();
		Vector rHat = dir.clone().normalize();//r.clone().normalize();
		
		Collection<Entity> nearbyEntsC =  w.getNearbyEntities(headPos, 16*12, 16*12, 16*12);
		ArrayList<Entity> nearbyEnts = new ArrayList<Entity>();
		for (Entity cEnt : nearbyEntsC)
			nearbyEnts.add(cEnt);
		
		Collections.sort(nearbyEnts, new SortByDistance(headPos.clone()));
		
		for(Entity ent : nearbyEnts) {
			if(r != null && ent.getLocation().clone().subtract(headPos).toVector().lengthSquared() >= r.lengthSquared())
				continue;
			Vector eR = ent.getLocation().clone().subtract(headPos).toVector().normalize();
			Vector delta = eR.subtract(rHat);
			if(Math.abs(delta.getX()) <= 0.1f && Math.abs(delta.getY()) <= 0.15f && Math.abs(delta.getZ()) <= 0.1f )
				return ent;
		}
		
		return null;
	}
	
	public Location getPreferredLocation(Location headPos, Location lookPos, World w, Vector dir) {
		Entity targetEntity = rayTrace(headPos, lookPos, w, dir);
		if(targetEntity != null) {
			//Bukkit.broadcastMessage("Targetting " + targetEntity.getName() + " at " + targetEntity.getLocation().toVector().toString());
			return targetEntity.getLocation();
		}
		/*if(lookPos != null)
			Bukkit.broadcastMessage("Targetting block at " + lookPos.toVector().toString());*/
		return lookPos;
	}
	
	public void saveTurrets() {
		String fileSeparator = System.getProperty("file.separator");
		String path = "plugins" + fileSeparator + "HoodCraft" + fileSeparator + "turrets.txt";
		File turretFile = new File(path);
		turretFile.delete();
		String fileString = "";
		for(DispenserTurret t : turrets) {
			Location turretLoc = t.dispenser.getLocation();
			fileString += turretLoc.getWorld().getName() + ";" + turretLoc.getBlockX() + ";" + turretLoc.getBlockY() + ";" + turretLoc.getBlockZ() + ";" + t.getOwner() + ";" + t.getMode() + "\n";		
		}
		
		try {
			FileOutputStream fos = new FileOutputStream(path);
			fos.write(fileString.getBytes());
			fos.flush();
			fos.close();
		} catch (IOException e) {
			//e.printStackTrace();
		}
	}
	
	public void loadTurrets() {
		String fileSeparator = System.getProperty("file.separator");
		String path = "plugins" + fileSeparator + "HoodCraft" + fileSeparator + "turrets.txt";
		try {
			BufferedReader inFile = new BufferedReader(new FileReader(path));
			String currentCoords;
			String[] locString;
			String owner;
			String mode;
			int x,y,z;
			Location currentLocation;
			do {
				currentCoords = inFile.readLine();
				if(currentCoords == null) break;
				locString = currentCoords.split(";");
				x = Integer.parseInt(locString[1]);
				y = Integer.parseInt(locString[2]);
				z = Integer.parseInt(locString[3]);
				owner = locString[4];
				mode = locString[5];
				Location turretLoc = new Location(Bukkit.getWorld(locString[0]), x, y, z);
				Block turretBlock = Bukkit.getWorld(locString[0]).getBlockAt(turretLoc);
				if(turretBlock.getType().equals(Material.DISPENSER)) {
					addTurret(turretBlock);
					getDispenserTurret(turretBlock).owner = owner;
					getDispenserTurret(turretBlock).setMode(mode);
				}
					
			} while(currentCoords != null);
		} catch (IOException e) {
			//e.printStackTrace();
		}
	}
	
	public int countTurrets(Player p) {
		int cnt = 0;
		for(DispenserTurret t : turrets)
			if(t.isOwner(p)) cnt++;
		return cnt;
	}
	
	@SuppressWarnings("deprecation")
	@EventHandler
	public void onPlayerInteract(PlayerInteractEvent event) {
		
		Player p = event.getPlayer();
		
		if(event.getAction().equals(Action.RIGHT_CLICK_BLOCK)) {
			Block clicked = event.getClickedBlock();
			if(clicked.getType().equals(Material.DISPENSER)) {
				DispenserTurret clickedTurret = getDispenserTurret(clicked);
				if(clickedTurret == null) return;
				boolean hasSomethingInHand = !p.getItemInHand().getType().equals(Material.AIR);
				if(p.isSneaking() && !hasSomethingInHand) {
					clickedTurret.sendTurretInfo(p);
					event.setCancelled(true);
				}
				else if(!clickedTurret.isOwner(p)) {
					p.sendMessage(ChatColor.DARK_RED + "You do not have access to this turret! It belongs to " + clickedTurret.getOwner());
					event.setCancelled(true);
				}
			}
		}
		
		if(event.getAction().equals(Action.RIGHT_CLICK_AIR) || event.getAction().equals(Action.RIGHT_CLICK_BLOCK)) {
			if(p.getItemInHand().getType() == Material.STICK && (p.getItemInHand().getItemMeta().getDisplayName() == "Doom Staff" || p.getItemInHand().getItemMeta().getDisplayName().matches("Doom Staff"))) {
				Block lookingAtBlock = p.getTargetBlockExact(16*12);
				Location lookingAt = null;
				if(lookingAtBlock != null)
					lookingAt = lookingAtBlock.getLocation();
				lookingAt = getPreferredLocation(p.getEyeLocation(), lookingAt, p.getWorld(), p.getLocation().getDirection().clone());
				if(lookingAt != null)
				for(Entity ent : p.getWorld().getNearbyEntities(lookingAt, 10, 10, 10)) {
					Location loc = ent.getLocation();
					ent.setGlowing(false);
				}
			
			}
		}
		
		if(event.getAction().equals(Action.LEFT_CLICK_AIR) || event.getAction().equals(Action.LEFT_CLICK_BLOCK)) {
				if(p.getItemInHand().getType() == Material.STICK && (p.getItemInHand().getItemMeta().getDisplayName() == "Doom Staff" || p.getItemInHand().getItemMeta().getDisplayName().matches("Doom Staff"))) {
					
					Block lookingAtBlock = p.getTargetBlockExact(16*12);
					Location lookingAt = null;
					if(lookingAtBlock != null)
						lookingAt = lookingAtBlock.getLocation();
					lookingAt = getPreferredLocation(p.getEyeLocation(), lookingAt, p.getWorld(), p.getLocation().getDirection().clone());
					if(lookingAt == null || !castSpell(p)) {
						
						return;
					}
					
					for(int dx=-10; dx <= 10; dx++) {
						for(int dz=-10; dz <= 10; dz++) {
							for(int dy=-10; dy <= 10; dy ++) {
								if((dx > -10 && dx < 10) && (dz > -10 && dz < 10) && (dy > -10 && dy < 10))continue;
								p.getWorld().spawnParticle(Particle.FIREWORKS_SPARK,new Location(p.getWorld(),lookingAt.getBlockX()+dx,lookingAt.getBlockY()+dy,lookingAt.getBlockZ()+dz), 1);;
							}
						}
					}
					for(Entity ent : p.getWorld().getNearbyEntities(lookingAt, 10, 10, 10)) {
						if(ent.equals(p)) continue;
						if(p.isSneaking()) {
							Location loc = ent.getLocation();
							ent.setGlowing(true);
							/*for(int height=loc.getBlockY()-5;height <= loc.getBlockY()+5; height++) {
								if(p.getWorld().getBlockAt((int)loc.getX()-1,(int)height,(int)loc.getBlockZ()).getType() == Material.AIR || p.getWorld().getBlockAt((int)loc.getX()-1,(int)height,(int)loc.getBlockZ()).getType() == Material.GRASS)
									p.getWorld().getBlockAt((int)loc.getX()-1,(int)height,(int)loc.getBlockZ()).setType(Material.ICE);
								if(p.getWorld().getBlockAt((int)loc.getX()+1,(int)height,(int)loc.getBlockZ()).getType() == Material.AIR || p.getWorld().getBlockAt((int)loc.getX()+1,(int)height,(int)loc.getBlockZ()).getType() == Material.GRASS)
									p.getWorld().getBlockAt((int)loc.getX()+1,(int)height,(int)loc.getBlockZ()).setType(Material.ICE);
								if(p.getWorld().getBlockAt((int)loc.getX(),(int)height,(int)loc.getBlockZ()-1).getType() == Material.AIR || p.getWorld().getBlockAt((int)loc.getX(),(int)height,(int)loc.getBlockZ()-1).getType() == Material.GRASS)
									p.getWorld().getBlockAt((int)loc.getX(),(int)height,(int)loc.getBlockZ()-1).setType(Material.ICE);
								if(p.getWorld().getBlockAt((int)loc.getX(),(int)height,(int)loc.getBlockZ()+1).getType() == Material.AIR || p.getWorld().getBlockAt((int)loc.getX(),(int)height,(int)loc.getBlockZ()+1).getType() == Material.GRASS)
									p.getWorld().getBlockAt((int)loc.getX(),(int)height,(int)loc.getBlockZ()+1).setType(Material.ICE);
								if(p.getWorld().getBlockAt((int)loc.getX(),(int)height,(int)loc.getBlockZ()).getType() == Material.AIR || p.getWorld().getBlockAt((int)loc.getX(),(int)height,(int)loc.getBlockZ()).getType() == Material.GRASS)
									p.getWorld().getBlockAt((int)loc.getX(),(int)height,(int)loc.getBlockZ()).setType(Material.WATER);
							}
							if(p.getWorld().getBlockAt((int)loc.getX(),(int)loc.getBlockY()+6,(int)loc.getBlockZ()).getType() == Material.AIR)
								p.getWorld().getBlockAt((int)loc.getX(),(int)loc.getBlockY()+6,(int)loc.getBlockZ()).setType(Material.ICE);
							if(p.getWorld().getBlockAt((int)loc.getX(),(int)loc.getBlockY()-1,(int)loc.getBlockZ()).getType() == Material.AIR)
								p.getWorld().getBlockAt((int)loc.getX(),(int)loc.getBlockY()-1,(int)loc.getBlockZ()).setType(Material.ICE);
							else if(p.getWorld().getBlockAt((int)loc.getX(),(int)loc.getBlockY()-1,(int)loc.getBlockZ()).getType() == Material.WATER)
								p.getWorld().getBlockAt((int)loc.getX(),(int)loc.getBlockY()-1,(int)loc.getBlockZ()).setType(Material.ICE);*/
							if(p.getWorld().getBlockAt((int)loc.getX(),(int)loc.getBlockY(),(int)loc.getBlockZ()).getType() == Material.AIR)
								p.getWorld().getBlockAt((int)loc.getX(),(int)loc.getBlockY(),(int)loc.getBlockZ()).setType(Material.COBWEB);
							else if(p.getWorld().getBlockAt((int)loc.getX(),(int)loc.getBlockY(),(int)loc.getBlockZ()).getType() == Material.GRASS)
								p.getWorld().getBlockAt((int)loc.getX(),(int)loc.getBlockY(),(int)loc.getBlockZ()).setType(Material.COBWEB);
							p.damage(5.0, ent);
						}
						else {
							ent.setGlowing(true);
							ent.setVelocity(new Vector(0f,5f,0f));
							p.damage(1.0f,ent);
						}
					};
				}
				
				if(p.getItemInHand().getType() == Material.STICK && (p.getItemInHand().getItemMeta().getDisplayName() == "Lightning Staff" || p.getItemInHand().getItemMeta().getDisplayName().matches("Lightning Staff"))) {
	
					Block lookingAtBlock = p.getTargetBlockExact(16*12);
					Location lookingAt = null;
					if(lookingAtBlock != null)
						lookingAt = lookingAtBlock.getLocation();
					lookingAt = getPreferredLocation(p.getEyeLocation(), lookingAt, p.getWorld(), p.getLocation().getDirection().clone());
					if(lookingAt == null || !castSpell(p)) {
						return;
					}
					for(int i=0; i < 10; i++) {
						Location target = new Location(p.getWorld(), lookingAt.getX()-2+random.nextInt(5),lookingAt.getY(),lookingAt.getZ()-2+random.nextInt(5) );
						p.getWorld().strikeLightning(target);
						if(random.nextInt(3) == 0)i--;
					}
					
					BukkitTask task = new LightningStriker(this, 4, lookingAt, p.getWorld()).runTaskTimer(this, 5, 5);
					p.damage(4.0);
				}
		}
		
		if(event.getAction().equals(Action.LEFT_CLICK_BLOCK)){
			Block clicked = event.getClickedBlock();
			Location clickedPos = clicked.getLocation();
			if(clicked.getType() == Material.COAL_BLOCK) {
				if(random .nextInt(200) == 1){
					clicked.setType(Material.AIR);
					//Bukkit.broadcastMessage(p.getDisplayName() + " successfully made a diamond from coal!");
					p.getWorld().dropItemNaturally(clickedPos, new ItemStack(Material.DIAMOND,1));
				}
			}
			if(clicked.getType().equals(Material.DISPENSER)) {
				if(!p.isSneaking()) return;
				boolean hasSomethingInHand = !p.getItemInHand().getType().equals(Material.AIR);
				if(hasSomethingInHand) return;
				if(getDispenserTurret(clicked) == null && countTurrets(p) >= 5) {
					p.sendMessage(ChatColor.DARK_RED + "Sorry, you have reached your turret limit(5).");
					return;
				}
				if(addTurret(clicked)) {
					p.sendMessage("You have created a new turret. Ensure that it has arrows to target the entities you specified in the written book!");
					DispenserTurret clickedTurret = getDispenserTurret(clicked);
					clickedTurret.setOwner(p);
					saveTurrets();
				}
				else {
					DispenserTurret clickedTurret = getDispenserTurret(clicked);
					if(!clickedTurret.isOwner(p)) {
						p.sendMessage("This turret belongs to " + clickedTurret.getOwner());
					}
					else {
						String newMode = clickedTurret.toggleMode();
						if(newMode == "all")
							p.sendMessage("Targetting mode changed to: " + ChatColor.RED + newMode);
						else if(newMode == "whitelist")
							p.sendMessage("Targetting mode changed to: " + ChatColor.GREEN + newMode);
						else if(newMode == "blacklist")
							p.sendMessage("Targetting mode changed to: " + ChatColor.BLUE + newMode);
						saveTurrets();
					}
				}
			}
			
			if(clicked.getType() == Material.BEDROCK) {
				MainHand hand = p.getMainHand();
				ItemStack handItem = p.getItemInHand();
				if(handItem.getType().equals(Material.DIAMOND_PICKAXE)) {
					if(handItem.getEnchantmentLevel(Enchantment.DIG_SPEED) < 5 || handItem.getDurability() > 0) {
						p.sendMessage(ChatColor.RED + "You need a diamond pickaxe with full durability and efficiency 5 or greater to break bedrock!");
					}
					else {
						if(attackBlock(p, clicked)) {
							clicked.setType(Material.AIR);
							p.playSound(clickedPos, Sound.BLOCK_STONE_BREAK, 1000f,0.1f);
							if(handItem.getEnchantmentLevel(Enchantment.SILK_TOUCH) != 0)
								p.getWorld().dropItemNaturally(clickedPos, new ItemStack(Material.BEDROCK,1));
							else
								p.sendMessage(ChatColor.BLUE + "Use silk touch next time if you want the bedrock to drop.");
							handItem.setDurability((short)((short)Material.DIAMOND_PICKAXE.getMaxDurability()-(short)5));
						}
					}
				}
			}
		}
	}
	
}