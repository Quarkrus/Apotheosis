package shadows.apotheosis.deadly.gen;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import com.google.common.base.Preconditions;

import net.minecraft.block.Blocks;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentData;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.MobEntity;
import net.minecraft.entity.ai.attributes.Attributes;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.ai.goal.NearestAttackableTargetGoal;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.entity.monster.CreeperEntity;
import net.minecraft.inventory.EquipmentSlotType;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.ShootableItem;
import net.minecraft.potion.Effect;
import net.minecraft.potion.EffectInstance;
import net.minecraft.potion.Effects;
import net.minecraft.util.Hand;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraft.world.IServerWorld;
import net.minecraftforge.registries.ForgeRegistries;
import shadows.apotheosis.Apotheosis;
import shadows.apotheosis.deadly.config.DeadlyConfig;
import shadows.apotheosis.deadly.gen.WeightedGenerator.WorldFeatureItem;
import shadows.apotheosis.deadly.loot.LootRarity;
import shadows.apotheosis.deadly.reload.BossArmorManager;
import shadows.apotheosis.deadly.reload.AffixLootManager;
import shadows.apotheosis.ench.asm.EnchHooks;
import shadows.apotheosis.util.NameHelper;
import shadows.placebo.util.AttributeHelper;

/**
 * Setup information for bosses.
 * @author Shadows
 *
 */
public class BossFeatureItem extends WorldFeatureItem {

	//Default lists of boss potions/enchantments.
	public static final List<Effect> POTIONS = new ArrayList<>();

	public static final Predicate<Goal> IS_VILLAGER_ATTACK = a -> a instanceof NearestAttackableTargetGoal && ((NearestAttackableTargetGoal<?>) a).targetClass == VillagerEntity.class;
	protected final EntityType<?> entityEntry;
	protected AxisAlignedBB entityAABB;

	public BossFeatureItem(int weight, ResourceLocation entity) {
		super(weight);
		entityEntry = ForgeRegistries.ENTITIES.getValue(entity);
		Preconditions.checkNotNull(entityEntry, "Invalid BossItem (not an entity) created with reloc: " + entity);
	}

	public AxisAlignedBB getAABB(IServerWorld world) {
		if (entityAABB == null) entityAABB = entityEntry.create(world.getWorld()).getBoundingBox();
		if (entityAABB == null) entityAABB = new AxisAlignedBB(0, 0, 0, 1, 1, 1);
		return entityAABB;
	}

	@Override
	public void place(IServerWorld world, BlockPos pos, Random rand) {
		buildPlatform(world, pos, rand);
		MobEntity entity = spawnBoss(world, pos, rand);
		DeadlyFeature.debugLog(pos, "Boss " + entity.getName().getUnformattedComponentText());
	}

	public void buildPlatform(IServerWorld world, BlockPos pos, Random rand) {
		for (BlockPos p : BlockPos.getAllInBoxMutable(pos.add(-2, -1, -2), pos.add(2, 1, 2))) {
			world.setBlockState(p, Blocks.AIR.getDefaultState(), 2);
		}
		for (BlockPos p : BlockPos.getAllInBoxMutable(pos.add(-2, -2, -2), pos.add(2, -2, 2))) {
			world.setBlockState(p, DeadlyConfig.bossFillerBlock.getDefaultState(), 2);
		}
	}

	public MobEntity spawnBoss(IServerWorld world, BlockPos pos, Random rand) {
		MobEntity entity = (MobEntity) entityEntry.create(world.getWorld());
		initBoss(rand, entity);
		entity.setLocationAndAngles(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, rand.nextFloat() * 360.0F, 0.0F);
		world.addEntity(entity);
		return entity;
	}

	public static void initBoss(Random rand, MobEntity entity) {
		int duration = entity instanceof CreeperEntity ? 6000 : Integer.MAX_VALUE;
		int regen = DeadlyConfig.bossRegenLevel.generateInt(rand) - 1;
		if (regen >= 0) entity.addPotionEffect(new EffectInstance(Effects.REGENERATION, duration, regen));
		int res = DeadlyConfig.bossResistLevel.generateInt(rand) - 1;
		if (res >= 0) entity.addPotionEffect(new EffectInstance(Effects.RESISTANCE, duration, res));
		if (rand.nextFloat() < DeadlyConfig.bossFireRes) entity.addPotionEffect(new EffectInstance(Effects.FIRE_RESISTANCE, duration));
		if (rand.nextFloat() < DeadlyConfig.bossWaterBreathing) entity.addPotionEffect(new EffectInstance(Effects.WATER_BREATHING, duration));
		AttributeHelper.multiplyFinal(entity, Attributes.ATTACK_DAMAGE, "boss_damage_bonus", DeadlyConfig.bossDamageMult.generateFloat(rand) - 1);
		AttributeHelper.multiplyFinal(entity, Attributes.MAX_HEALTH, "boss_health_mult", DeadlyConfig.bossHealthMultiplier.generateFloat(rand) - 1);
		AttributeHelper.addToBase(entity, Attributes.KNOCKBACK_RESISTANCE, "boss_knockback_resist", DeadlyConfig.bossKnockbackResist.generateFloat(rand));
		AttributeHelper.multiplyFinal(entity, Attributes.MOVEMENT_SPEED, "boss_speed_mult", DeadlyConfig.bossSpeedMultiplier.generateFloat(rand) - 1);
		entity.setHealth(entity.getMaxHealth());
		entity.goalSelector.goals.removeIf(IS_VILLAGER_ATTACK);
		entity.enablePersistence();
		String name = NameHelper.setEntityName(rand, entity);

		BossArmorManager.INSTANCE.getRandomSet(rand).apply(entity);

		if (entity.func_230280_a_((ShootableItem) Items.BOW) && rand.nextBoolean()) entity.setHeldItem(Hand.MAIN_HAND, new ItemStack(Items.BOW));
		else if (entity.func_230280_a_((ShootableItem) Items.CROSSBOW) && rand.nextBoolean()) entity.setHeldItem(Hand.MAIN_HAND, new ItemStack(Items.CROSSBOW));

		int guaranteed = rand.nextInt(6);

		ItemStack stack = entity.getItemStackFromSlot(EquipmentSlotType.values()[guaranteed]);
		while (guaranteed == 1 || stack.isEmpty())
			stack = entity.getItemStackFromSlot(EquipmentSlotType.values()[guaranteed = rand.nextInt(6)]);

		for (EquipmentSlotType s : EquipmentSlotType.values()) {
			if (s.ordinal() == guaranteed) entity.setDropChance(s, 2F);
			else entity.setDropChance(s, ThreadLocalRandom.current().nextFloat() / 2);
			if (s.ordinal() == guaranteed) {
				entity.setItemStackToSlot(s, modifyBossItem(stack, rand, name));
			} else if (rand.nextDouble() < DeadlyConfig.bossEnchantChance) {
				List<EnchantmentData> ench = EnchantmentHelper.buildEnchantmentList(rand, stack, 30 + rand.nextInt(Apotheosis.enableEnch ? 20 : 10), true);
				EnchantmentHelper.setEnchantments(ench.stream().filter(d -> !d.enchantment.isCurse()).collect(Collectors.toMap(d -> d.enchantment, d -> d.enchantmentLevel, (v1, v2) -> Math.max(v1, v2), HashMap::new)), stack);
			}
		}

		if (POTIONS.isEmpty()) initPotions();

		if (rand.nextDouble() < DeadlyConfig.bossPotionChance) entity.addPotionEffect(new EffectInstance(POTIONS.get(rand.nextInt(POTIONS.size())), duration, rand.nextInt(3) + 1));
	}

	public static void initPotions() {
		for (Effect p : ForgeRegistries.POTIONS)
			if (p.isBeneficial() && !p.isInstant()) POTIONS.add(p);
		POTIONS.removeIf(p -> DeadlyConfig.BLACKLISTED_POTIONS.contains(p.getRegistryName()));
	}

	public static ItemStack modifyBossItem(ItemStack stack, Random random, String bossName) {
		List<EnchantmentData> ench = EnchantmentHelper.buildEnchantmentList(random, stack, Apotheosis.enableEnch ? 75 : 30, true);
		EnchantmentHelper.setEnchantments(ench.stream().filter(d -> !d.enchantment.isCurse()).collect(Collectors.toMap(d -> d.enchantment, d -> d.enchantmentLevel, (a, b) -> Math.max(a, b))), stack);
		String itemName = NameHelper.setItemName(random, stack, bossName);
		stack.setDisplayName(new StringTextComponent(itemName));
		LootRarity rarity = LootRarity.random(random, DeadlyConfig.bossRarityOffset);
		stack = AffixLootManager.genLootItem(stack, random, rarity);
		stack.setDisplayName(new TranslationTextComponent("%s %s", TextFormatting.RESET + rarity.getColor().toString() + String.format(NameHelper.ownershipFormat, bossName), stack.getDisplayName()).mergeStyle(rarity.getColor()));
		Map<Enchantment, Integer> enchMap = new HashMap<>();
		for (Entry<Enchantment, Integer> e : EnchantmentHelper.getEnchantments(stack).entrySet()) {
			if (e.getKey() != null) enchMap.put(e.getKey(), Math.min(EnchHooks.getMaxLevel(e.getKey()), e.getValue() + random.nextInt(2)));
		}
		EnchantmentHelper.setEnchantments(enchMap, stack);
		stack.getTag().putBoolean("apoth_boss", true);
		return stack;
	}
}