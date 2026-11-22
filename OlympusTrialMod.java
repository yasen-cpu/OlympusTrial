package com.cybermod.olympustrial;

import java.util.List;
import org.joml.Vector3f;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

@Mod(OlympusTrialMod.MOD_ID)
public class OlympusTrialMod {
    public static final String MOD_ID = "olympus_trial";
    private static final String PROTOCOL = "2";
    private static final int MAX_MAGIC = 100;

    private static final String MAGIC_KEY = "OlympusMagic";
    private static final String ORBS_KEY = "OlympusRedOrbs";
    private static final String INITIALIZED_KEY = "OlympusInitialized";
    private static final String STARTER_KEY = "OlympusStarterGiven";
    private static final String PETRIFIED_TICKS = "OlympusPetrifiedTicks";
    private static final String OFFHAND_CLONE_TAG = "OlympusOffhandClone";

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, MOD_ID);

    public static final RegistryObject<Item> BLADES = ITEMS.register(
            "blades_of_athena",
            BladesItem::new
    );

    public static final RegistryObject<Item> CRONOS = ITEMS.register(
            "cronos_power",
            () -> new PowerItem(Power.CRONOS)
    );

    public static final RegistryObject<Item> BOW = ITEMS.register(
            "bow_of_destiny",
            () -> new PowerItem(Power.BOW)
    );

    public static final RegistryObject<Item> MEDUSA = ITEMS.register(
            "medusa_head",
            () -> new PowerItem(Power.MEDUSA)
    );

    public static final RegistryObject<Item> ATLAS = ITEMS.register(
            "atlas_quake",
            () -> new PowerItem(Power.ATLAS)
    );

    private static final SimpleChannel CHANNEL =
            NetworkRegistry.ChannelBuilder
                    .named(new ResourceLocation(MOD_ID, "main"))
                    .networkProtocolVersion(() -> PROTOCOL)
                    .clientAcceptedVersions(PROTOCOL::equals)
                    .serverAcceptedVersions(PROTOCOL::equals)
                    .simpleChannel();

    public OlympusTrialMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ITEMS.register(modBus);
        modBus.addListener(this::addCreativeItems);

        CHANNEL.messageBuilder(HudSyncPacket.class, 0, NetworkDirection.PLAY_TO_CLIENT)
                .encoder((msg, buf) -> {
                    buf.writeInt(msg.magic);
                    buf.writeInt(msg.redOrbs);
                })
                .decoder(HudSyncPacket::decode)
                .consumerMainThread((msg, ctx) -> {
                    ClientState.magic = msg.magic;
                    ClientState.redOrbs = msg.redOrbs;
                    ctx.get().setPacketHandled(true);
                })
                .add();

        MinecraftForge.EVENT_BUS.register(ForgeEvents.class);
    }

    private void addCreativeItems(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.COMBAT) {
            event.accept(BLADES);
            event.accept(CRONOS);
            event.accept(BOW);
            event.accept(MEDUSA);
            event.accept(ATLAS);
        }
    }

    private enum Power {
        CRONOS, BOW, MEDUSA, ATLAS
    }

    private static CompoundTag playerData(Player player) {
        CompoundTag root = player.getPersistentData();
        if (!root.contains(Player.PERSISTED_NBT_TAG)) {
            root.put(Player.PERSISTED_NBT_TAG, new CompoundTag());
        }
        return root.getCompound(Player.PERSISTED_NBT_TAG);
    }

    private static int getMagic(Player player) {
        CompoundTag data = playerData(player);
        if (!data.getBoolean(INITIALIZED_KEY)) {
            data.putBoolean(INITIALIZED_KEY, true);
            data.putInt(MAGIC_KEY, MAX_MAGIC);
            data.putInt(ORBS_KEY, 0);
        }
        return Math.max(0, Math.min(MAX_MAGIC, data.getInt(MAGIC_KEY)));
    }

    private static int getRedOrbs(Player player) {
        return playerData(player).getInt(ORBS_KEY);
    }

    private static void addRedOrbs(Player player, int amount) {
        CompoundTag data = playerData(player);
        data.putInt(ORBS_KEY, data.getInt(ORBS_KEY) + amount);
        syncHud(player);
    }

    private static void setMagic(Player player, int amount) {
        playerData(player).putInt(MAGIC_KEY, Math.max(0, Math.min(MAX_MAGIC, amount)));
        syncHud(player);
    }

    private static void syncHud(Player player) {
        if (player instanceof ServerPlayer sp) {
            CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> sp),
                    new HudSyncPacket(getMagic(player), getRedOrbs(player))
            );
        }
    }

    private static boolean spendMagic(Player player, int amount) {
        int current = getMagic(player);
        if (current < amount) {
            player.displayClientMessage(Component.literal("§c§l⚡ NOT ENOUGH GOD MAGIC!"), true);
            player.level().playSound(null, player.blockPosition(), SoundEvents.NOTE_BLOCK_BASS.value(), SoundSource.PLAYERS, 1.0F, 0.5F);
            return false;
        }
        setMagic(player, current - amount);
        return true;
    }

    private static List<LivingEntity> nearbyTargets(Player player, double radius) {
        AABB area = player.getBoundingBox().inflate(radius);
        return player.level().getEntitiesOfClass(
                LivingEntity.class,
                area,
                target -> target != player && target.isAlive() && player.distanceToSqr(target) <= radius * radius
        );
    }

    private static void draw3DChain(ServerLevel level, Vec3 from, Vec3 to, DustParticleOptions dust) {
        double dist = from.distanceTo(to);
        int steps = Math.max(8, (int) (dist * 5));
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            double x = from.x + (to.x - from.x) * t;
            double y = from.y + (to.y - from.y) * t + Math.sin(t * Math.PI) * 0.25;
            double z = from.z + (to.z - from.z) * t;
            level.sendParticles(dust, x, y, z, 1, 0.02, 0.02, 0.02, 0.0);
            if (i % 2 == 0) {
                level.sendParticles(ParticleTypes.FLAME, x, y, z, 1, 0.02, 0.02, 0.02, 0.005);
            }
        }
    }

    private static void giveStarterItem(Player player, Item item) {
        ItemStack stack = new ItemStack(item);
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
    }

    @Mod.EventBusSubscriber(modid = MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
    public static class ForgeEvents {

        @SubscribeEvent
        public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
            Player player = event.getEntity();
            if (player.level().isClientSide) return;

            CompoundTag data = playerData(player);
            if (!data.getBoolean(STARTER_KEY)) {
                data.putBoolean(STARTER_KEY, true);
                giveStarterItem(player, BLADES.get());
                giveStarterItem(player, CRONOS.get());
                giveStarterItem(player, BOW.get());
                giveStarterItem(player, MEDUSA.get());
                giveStarterItem(player, ATLAS.get());
            }
            syncHud(player);
        }

        @SubscribeEvent
        public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            Player player = event.player;
            if (player.level().isClientSide) return;

            ItemStack main = player.getMainHandItem();
            ItemStack off = player.getOffhandItem();

            if (main.is(BLADES.get())) {
                if (off.isEmpty()) {
                    ItemStack dualClone = new ItemStack(BLADES.get());
                    dualClone.getOrCreateTag().putBoolean(OFFHAND_CLONE_TAG, true);
                    player.setItemInHand(InteractionHand.OFF_HAND, dualClone);
                }
            } else {
                if (off.is(BLADES.get()) && off.hasTag() && off.getTag().getBoolean(OFFHAND_CLONE_TAG)) {
                    player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
                }
            }

            if (player.tickCount % 20 == 0) {
                int current = getMagic(player);
                if (current < MAX_MAGIC) {
                    setMagic(player, current + 3);
                } else {
                    syncHud(player);
                }
            }
        }

        @SubscribeEvent
        public static void onLevelTick(TickEvent.LevelTickEvent event) {
            if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel serverLevel)) return;
            if (serverLevel.getGameTime() % 5 != 0) return;

            for (var entity : serverLevel.getAllEntities()) {
                if (entity instanceof LivingEntity living && living.isAlive()) {
                    int ticks = living.getPersistentData().getInt(PETRIFIED_TICKS);
                    if (ticks > 0) {
                        ticks -= 5;
                        living.getPersistentData().putInt(PETRIFIED_TICKS, Math.max(0, ticks));
                        serverLevel.sendParticles(
                                new BlockParticleOption(ParticleTypes.BLOCK, Blocks.STONE.defaultBlockState()),
                                living.getX(), living.getY() + living.getBbHeight() * 0.5, living.getZ(),
                                8, 0.35, 0.6, 0.35, 0.0
                        );
                        if (ticks <= 0 && living instanceof Mob mob) {
                            mob.setNoAi(false);
                        }
                    }
                }
            }
        }

        @SubscribeEvent
        public static void onLivingHurt(LivingHurtEvent event) {
            LivingEntity target = event.getEntity();
            if (target.level().isClientSide || !(target.level() instanceof ServerLevel serverLevel)) return;

            if (target.getPersistentData().getInt(PETRIFIED_TICKS) > 0) {
                target.getPersistentData().putInt(PETRIFIED_TICKS, 0);
                if (target instanceof Mob mob) mob.setNoAi(false);

                event.setAmount(target.getMaxHealth() * 10.0F);
                serverLevel.sendParticles(
                        new BlockParticleOption(ParticleTypes.BLOCK, Blocks.COBBLESTONE.defaultBlockState()),
                        target.getX(), target.getY() + 1.0, target.getZ(),
                        90, 0.5, 0.8, 0.5, 0.2
                );
                serverLevel.playSound(null, target.blockPosition(), SoundEvents.STONE_BREAK, SoundSource.PLAYERS, 2.5F, 0.6F);
                serverLevel.playSound(null, target.blockPosition(), SoundEvents.GLASS_BREAK, SoundSource.PLAYERS, 1.8F, 0.5F);
            }
        }

        @SubscribeEvent
        public static void onLivingDeath(LivingDeathEvent event) {
            if (event.getSource().getEntity() instanceof Player player && !player.level().isClientSide) {
                addRedOrbs(player, 15);
                if (player.level() instanceof ServerLevel serverLevel) {
                    LivingEntity dead = event.getEntity();
                    DustParticleOptions redOrb = new DustParticleOptions(new Vector3f(1.0F, 0.1F, 0.1F), 1.8F);
                    draw3DChain(serverLevel, dead.position().add(0, 1, 0), player.position().add(0, 1.2, 0), redOrb);
                }
            }
        }
    }

    public static class BladesItem extends SwordItem {
        public BladesItem() {
            super(Tiers.NETHERITE, 9, -1.6F, new Item.Properties().rarity(Rarity.EPIC).fireResistant());
        }

        @Override
        public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
            tooltip.add(Component.literal("§6§l⚔ BLADES OF ATHENA (3D CHAINED BLADES)"));
            tooltip.add(Component.literal("§e• الضرب العادي: سحب الأعداء بالسلاسل النارية وحرقهم"));
            tooltip.add(Component.literal("§c• كليك يمين: إعصار سلاسل أثينا + انفجار Plume of Prometheus"));
        }

        @Override
        public boolean hurtEnemy(ItemStack stack, LivingEntity target, LivingEntity attacker) {
            if (!attacker.level().isClientSide && attacker.level() instanceof ServerLevel serverLevel) {
                DustParticleOptions chainGold = new DustParticleOptions(new Vector3f(1.0F, 0.65F, 0.0F), 1.4F);
                draw3DChain(serverLevel, attacker.getEyePosition().subtract(0, 0.4, 0), target.position().add(0, 1.0, 0), chainGold);

                Vec3 pull = attacker.position().subtract(target.position()).normalize().scale(0.65);
                target.setDeltaMovement(pull.x, 0.35, pull.z);
                target.hurtMarked = true;
                target.setSecondsOnFire(5);
                serverLevel.playSound(null, attacker.blockPosition(), SoundEvents.CHAIN_BREAK, SoundSource.PLAYERS, 1.5F, 0.8F);
            }
            return super.hurtEnemy(stack, target, attacker);
        }

        @Override
        public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
            ItemStack stack = player.getItemInHand(hand);
            if (!level.isClientSide && level instanceof ServerLevel serverLevel) {
                if (!spendMagic(player, 10)) return InteractionResultHolder.fail(stack);

                DustParticleOptions fieryChain = new DustParticleOptions(new Vector3f(1.0F, 0.35F, 0.0F), 1.6F);
                Vec3 origin = player.position().add(0, 1.1, 0);

                for (int angle = 0; angle < 360; angle += 30) {
                    double rad = Math.toRadians(angle);
                    Vec3 tip = origin.add(Math.cos(rad) * 7.5, 0.2, Math.sin(rad) * 7.5);
                    draw3DChain(serverLevel, origin, tip, fieryChain);
                }

                for (LivingEntity target : nearbyTargets(player, 8.0)) {
                    draw3DChain(serverLevel, origin, target.position().add(0, 1.0, 0), fieryChain);
                    target.hurt(level.damageSources().playerAttack(player), 14.0F);
                    target.setDeltaMovement(0, 0.95, 0);
                    target.hurtMarked = true;
                    target.setSecondsOnFire(6);
                }

                serverLevel.sendParticles(ParticleTypes.LAVA, player.getX(), player.getY() + 0.5, player.getZ(), 35, 3.0, 0.3, 3.0, 0.1);
                serverLevel.playSound(null, player.blockPosition(), SoundEvents.CHAIN_PLACE, SoundSource.PLAYERS, 2.0F, 0.6F);
                serverLevel.playSound(null, player.blockPosition(), SoundEvents.GENERIC_EXPLODE, SoundSource.PLAYERS, 1.2F, 1.1F);
                player.getCooldowns().addCooldown(this, 25);
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }
    }

    public static class PowerItem extends Item {
        private final Power power;

        public PowerItem(Power power) {
            super(new Item.Properties().stacksTo(1).rarity(Rarity.EPIC).fireResistant());
            this.power = power;
        }

        @Override
        public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
            switch (power) {
                case CRONOS -> {
                    tooltip.add(Component.literal("§b§l⚡ CRONOS' RAGE (غضب كرونوس)"));
                    tooltip.add(Component.literal("§7يطلق كرة برق تربط كل الأعداء بشبكة صواعق متصلة"));
                }
                case BOW -> {
                    tooltip.add(Component.literal("§e§l🏹 TYPHON'S BANE / BOW OF DESTINY"));
                    tooltip.add(Component.literal("§7يطلق وابل سهام ضوئية خارقة بدون استهلاك أسهم"));
                }
                case MEDUSA -> {
                    tooltip.add(Component.literal("§a§l🐍 HEAD OF EURYALE / MEDUSA GAZE"));
                    tooltip.add(Component.literal("§7شعاع أخضر يحول الوحوش إلى تماثيل حجرية تتفتت بضربة واحدة!"));
                }
                case ATLAS -> {
                    tooltip.add(Component.literal("§6§l🌋 ATLAS QUAKE (زلزال التايتن أطلس)"));
                    tooltip.add(Component.literal("§7يفجر الأرض ويقذف صخور وحمم ثلاثية الأبعاد في الهواء!"));
                }
            }
        }

        @Override
        public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
            ItemStack stack = player.getItemInHand(hand);
            if (level.isClientSide || !(level instanceof ServerLevel serverLevel)) {
                return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
            }

            int cost = switch (power) {
                case CRONOS -> 25;
                case BOW -> 5;
                case MEDUSA -> 20;
                case ATLAS -> 30;
            };

            if (!spendMagic(player, cost)) return InteractionResultHolder.fail(stack);

            switch (power) {
                case CRONOS -> useCronosRage(serverLevel, player);
                case BOW -> useBowOfDestiny(serverLevel, player);
                case MEDUSA -> useMedusaGaze(serverLevel, player);
                case ATLAS -> useAtlasQuake(serverLevel, player);
            }

            int cd = switch (power) {
                case CRONOS -> 60;
                case BOW -> 6;
                case MEDUSA -> 70;
                case ATLAS -> 90;
            };
            player.getCooldowns().addCooldown(this, cd);
            return InteractionResultHolder.sidedSuccess(stack, false);
        }
    }

    private static void useCronosRage(ServerLevel level, Player player) {
        DustParticleOptions cyanArc = new DustParticleOptions(new Vector3f(0.0F, 0.9F, 1.0F), 1.6F);
        List<LivingEntity> targets = nearbyTargets(player, 14.0);
        Vec3 prevPos = player.getEyePosition();

        for (LivingEntity target : targets) {
            Vec3 currentPos = target.position().add(0, 1.0, 0);
            draw3DChain(level, prevPos, currentPos, cyanArc);
            prevPos = currentPos;

            target.hurt(level.damageSources().lightningBolt(), 15.0F);
            LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level);
            if (bolt != null) {
                bolt.moveTo(target.position());
                level.addFreshEntity(bolt);
            }
        }
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK, player.getX(), player.getY() + 1.5, player.getZ(), 90, 3.0, 1.5, 3.0, 0.2);
        level.playSound(null, player.blockPosition(), SoundEvents.TRIDENT_THUNDER, SoundSource.PLAYERS, 2.0F, 1.2F);
    }

    private static void useBowOfDestiny(ServerLevel level, Player player) {
        Vec3 start = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        DustParticleOptions windBeam = new DustParticleOptions(new Vector3f(0.2F, 1.0F, 0.9F), 1.3F);

        for (int dist = 1; dist <= 32; dist++) {
            Vec3 point = start.add(look.scale(dist));
            level.sendParticles(windBeam, point.x, point.y, point.z, 2, 0.05, 0.05, 0.05, 0.0);
            level.sendParticles(ParticleTypes.END_ROD, point.x, point.y, point.z, 1, 0.0, 0.0, 0.0, 0.0);

            AABB hitBox = new AABB(point.x - 1.2, point.y - 1.2, point.z - 1.2, point.x + 1.2, point.y + 1.2, point.z + 1.2);
            List<LivingEntity> hitEntities = level.getEntitiesOfClass(LivingEntity.class, hitBox, e -> e != player && e.isAlive());
            if (!hitEntities.isEmpty()) {
                for (LivingEntity e : hitEntities) {
                    e.hurt(level.damageSources().playerAttack(player), 11.0F);
                }
                level.sendParticles(ParticleTypes.FLASH, point.x, point.y, point.z, 1, 0, 0, 0, 0);
                break;
            }
        }
        level.playSound(null, player.blockPosition(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 1.2F, 1.6F);
        level.playSound(null, player.blockPosition(), SoundEvents.ARROW_SHOOT, SoundSource.PLAYERS, 1.5F, 1.4F);
    }

    private static void useMedusaGaze(ServerLevel level, Player player) {
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        DustParticleOptions emeraldGaze = new DustParticleOptions(new Vector3f(0.1F, 1.0F, 0.2F), 1.8F);

        for (int d = 1; d <= 12; d++) {
            Vec3 center = eye.add(look.scale(d));
            level.sendParticles(emeraldGaze, center.x, center.y, center.z, d * 2, d * 0.15, d * 0.15, d * 0.15, 0.02);
        }

        for (LivingEntity target : nearbyTargets(player, 11.0)) {
            target.getPersistentData().putInt(PETRIFIED_TICKS, 160);
            if (target instanceof Mob mob) {
                mob.setNoAi(true);
            }
            target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 160, 10));
            level.sendParticles(
                    new BlockParticleOption(ParticleTypes.BLOCK, Blocks.STONE.defaultBlockState()),
                    target.getX(), target.getY() + 1.0, target.getZ(),
                    50, 0.4, 0.8, 0.4, 0.05
            );
        }

        level.playSound(null, player.blockPosition(), SoundEvents.ILLUSIONER_CAST_SPELL, SoundSource.PLAYERS, 2.0F, 0.5F);
        level.playSound(null, player.blockPosition(), SoundEvents.STONE_PLACE, SoundSource.PLAYERS, 2.0F, 0.6F);
    }

    private static void useAtlasQuake(ServerLevel level, Player player) {
        BlockPos center = player.blockPosition();

        for (int angle = 0; angle < 360; angle += 22) {
            double rad = Math.toRadians(angle);
            double rx = player.getX() + Math.cos(rad) * 3.5;
            double rz = player.getZ() + Math.sin(rad) * 3.5;
            BlockPos spawnPos = new BlockPos((int) rx, center.getY() + 1, (int) rz);

            var state = (angle % 44 == 0) ? Blocks.MAGMA_BLOCK.defaultBlockState() : Blocks.DEEPSLATE.defaultBlockState();
            FallingBlockEntity rock = FallingBlockEntity.fall(level, spawnPos, state);
            rock.dropItem = false;
            rock.setHurtsEntities(2.0F, 20);
            rock.setDeltaMovement(Math.cos(rad) * 0.35, 0.65, Math.sin(rad) * 0.35);
        }

        for (LivingEntity target : nearbyTargets(player, 10.0)) {
            target.hurt(level.damageSources().playerAttack(player), 18.0F);
            target.setDeltaMovement(0, 1.15, 0);
            target.hurtMarked = true;
            target.setSecondsOnFire(6);
        }

        level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, player.getX(), player.getY(), player.getZ(), 2, 2.0, 0.2, 2.0, 0.0);
        level.sendParticles(ParticleTypes.LAVA, player.getX(), player.getY() + 0.5, player.getZ(), 60, 4.0, 0.5, 4.0, 0.15);
        level.playSound(null, center, SoundEvents.GENERIC_EXPLODE, SoundSource.PLAYERS, 2.5F, 0.5F);
    }

    public static class HudSyncPacket {
        private final int magic;
        private final int redOrbs;

        public HudSyncPacket(int magic, int redOrbs) {
            this.magic = magic;
            this.redOrbs = redOrbs;
        }

        public static HudSyncPacket decode(FriendlyByteBuf buffer) {
            return new HudSyncPacket(buffer.readInt(), buffer.readInt());
        }
    }

    public static class ClientState {
        public static int magic = MAX_MAGIC;
        public static int redOrbs = 0;
    }

    @Mod.EventBusSubscriber(modid = MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientEvents {

        @SubscribeEvent
        public static void registerOverlays(RegisterGuiOverlaysEvent event) {
            event.registerAboveAll("olympus_gow2_hud", (gui, graphics, partialTick, width, height) -> {
                var mc = net.minecraft.client.Minecraft.getInstance();
                Player player = mc.player;
                if (player == null || mc.options.hideGui) return;

                graphics.fill(8, 8, 36, 38, 0xDD2A1500);
                graphics.fill(10, 10, 34, 36, 0xFFFFAA00);
                graphics.fill(13, 13, 31, 33, 0xFF1A0D00);
                graphics.drawString(mc.font, "§6§lΩ", 19, 19, 0xFFFFCC00, true);

                int barX = 40;
                int barW = 145;

                float hpRatio = Math.max(0.0F, Math.min(1.0F, player.getHealth() / player.getMaxHealth()));
                float mpRatio = Math.max(0.0F, Math.min(1.0F, ClientState.magic / (float) MAX_MAGIC));

                graphics.fill(barX - 2, 10, barX + barW + 2, 22, 0xFFAA8800);
                graphics.fill(barX, 12, barX + barW, 20, 0xCC002200);
                graphics.fill(barX, 12, barX + (int) (barW * hpRatio), 20, 0xFF11DD44);

                graphics.fill(barX - 2, 25, barX + barW + 2, 37, 0xFFAA8800);
                graphics.fill(barX, 27, barX + barW, 35, 0xCC001133);
                graphics.fill(barX, 27, barX + (int) (barW * mpRatio), 35, 0xFF00AAFF);

                String orbText = "§c§l● RED ORBS: §f" + ClientState.redOrbs;
                int orbW = mc.font.width(orbText) + 12;
                graphics.fill(width - orbW - 12, 10, width - 10, 26, 0xCC330000);
                graphics.drawString(mc.font, orbText, width - orbW - 6, 14, 0xFFFFFFFF, true);
            });
        }
    }
}
