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
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
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

    // رسم سلسلة ثلاثية الأبعاد من الجزيئات النارية والحديدية بين نقطتين في الفضاء
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

        // ميزة السيفين (Dual Wielding): إظهار النصل الثاني في اليد اليسرى تلقائياً عند مسك نصل أثينا!
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

        // تحديث الأعداء المتحجرين بواسطة رأس ميدوسا (إحاطتهم بجزيئات الحجر وتجميدهم)
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

        // تفتيت وتكسير التمثال الحجري فوراً عند ضربه بأي سلاح!
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

        // جمع الكرات الحمراء (Red Orbs) عند قتل الأعداء مثل لعبة God of War 2
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

    // ================= سلاح كريتوس: BLADES OF ATHENA 3D =================
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

        // عند ضرب العدو: رسم سلاسل نارية ثلاثية الأبعاد وسحبه نحو كريتوس
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

        // كليك يمين: إطلاق السلاسل في دائرة 360 درجة وضربة Plume of Prometheus الأرضية
        @Override
        public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
            ItemStack stack = player.getItemInHand(hand);
            if (!level.isClientSide && level instanceof ServerLevel serverLevel) {
                if (!spendMagic(player, 10)) return InteractionResultHolder.fail(stack);

                DustParticleOptions fieryChain = new DustParticleOptions(new Vector3f(1.0F, 0.35F, 0.0F), 1.6F);
                Vec3 origin = player.position().add(0, 1.1, 0);

                // رسم 12 سلسلة نارية ثلاثية الأبعاد تدور حول كريتوس
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

    // ================= قدرات الآلهة والتايتنز (GOD & TITAN POWERS) =================
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

    // 1. قدرة كرونوس: شبكة برق ثلاثية الأبعاد تربط الأعداء ببعضهم وتصعقهم
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

    // 2. قوس القدر: شعاع سهام ضوئية ثلاثية الأبعاد فائقة السرعة
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
        level.playSound(null, player.blockPosition(), SoundEvents.BREEZE_IDLE_GROUND, SoundSource.PLAYERS, 1.2F, 1.6F);
        level.playSound(null, player.blockPosition(), SoundEvents.ARROW_SHOOT, SoundSource.PLAYERS, 1.5F, 1.4F);
    }

    // 3. رأس ميدوسا: شعاع زمردي يحول الوحوش إلى تماثيل حجرية حقيقية!
    private static void useMedusaGaze(ServerLevel level, Player player) {
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        DustParticleOptions emeraldGaze = new DustParticleOptions(new Vector3f(0.1F, 1.0F, 0.2F), 1.8F);

        // رسم مخروط الشعاع الأخضر أمام رأس ميدوسا
        for (int d = 1; d <= 12; d++) {
            Vec3 center = eye.add(look.scale(d));
            level.sendParticles(emeraldGaze, center.x, center.y, center.z, d * 2, d * 0.15, d * 0.15, d * 0.15, 0.02);
        }

        for (LivingEntity target : nearbyTargets(player, 11.0)) {
            target.getPersistentData().putInt(PETRIFIED_TICKS, 160); // تجميد حجري لمدة 8 ثوانٍ
            if (target instanceof Mob mob) {
                mob.setNoAi(true); // إيقاف حركة وذكاء الوحش تماماً كتمثال!
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

    // 4. زلزال أطلس: ثوران صخور وحمم ثلاثية الأبعاد حقيقية من الأرض!
    private static void useAtlasQuake(ServerLevel level, Player player) {
        BlockPos center = player.blockPosition();

        // قذف 16 صخرة بركانية ثلاثية الأبعاد في الهواء حول اللاعب
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

    // ================= واجهة GOD OF WAR 2 الاحترافية أعلى الشاشة =================
    @Mod.EventBusSubscriber(modid = MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientEvents {

        @SubscribeEvent
        public static void registerOverlays(RegisterGuiOverlaysEvent event) {
            event.registerAboveAll("olympus_gow2_hud", (gui, graphics, partialTick, width, height) -> {
                var mc = net.minecraft.client.Minecraft.getInstance();
                Player player = mc.player;
                if (player == null || mc.options.hideGui) return;

                // رسم شعار الأوميجا الذهبي Ω خلف الأشرطة
                graphics.fill(8, 8, 36, 38, 0xDD2A1500);
                graphics.fill(10, 10, 34, 36, 0xFFFFAA00);
                graphics.fill(13, 13, 31, 33, 0xFF1A0D00);
                graphics.drawString(mc.font, "§6§lΩ", 19, 19, 0xFFFFCC00, true);

                int barX = 40;
                int barW = 145;

                float hpRatio = Math.max(0.0F, Math.min(1.0F, player.getHealth() / player.getMaxHealth()));
                float mpRatio = Math.max(0.0F, Math.min(1.0F, ClientState.magic / (float) MAX_MAGIC));

                // إطار شريط صحة كريتوس (الأخضر الزمردي مثل God of War 2)
                graphics.fill(barX - 2, 10, barX + barW + 2, 22, 0xFFAA8800);
                graphics.fill(barX, 12, barX + barW, 20, 0xCC002200);
                graphics.fill(barX, 12, barX + (int) (barW * hpRatio), 20, 0xFF11DD44);

                // إطار شريط السحر (الأزرق السماوي)
                graphics.fill(barX - 2, 25, barX + barW + 2, 37, 0xFFAA8800);
                graphics.fill(barX, 27, barX + barW, 35, 0xCC001133);
                graphics.fill(barX, 27, barX + (int) (barW * mpRatio), 35, 0xFF00AAFF);

                // عداد الكرات الحمراء (Red Orbs) أعلى اليمين
                String orbText = "§c§l● RED ORBS: §f" + ClientState.redOrbs;
                int orbW = mc.font.width(orbText) + 12;
                graphics.fill(width - orbW - 12, 10, width - 10, 26, 0xCC330000);
                graphics.drawString(mc.font, orbText, width - orbW - 6, 14, 0xFFFFFFFF, true);
            });
        }
    }
}
```

---

### الخطوة 2: عدّل ملف `.github/workflows/build.yml` (لبناء المجسمات الـ 3D والـ Textures المخصصة)
افتح ملف `.github/workflows/build.yml` على GitHub، امسح اللي جواه وحط الكود ده بالكامل واضغط **Commit changes**:

```yaml
name: Build Olympus GOW2 3D Mod

on:
  push:
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-latest

    steps:
      - name: Checkout repository
        uses: actions/checkout@v4

      - name: Set up Java 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'

      - name: Download Forge MDK & Generate 3D Models + Custom Textures
        shell: bash
        run: |
          set -euo pipefail

          mkdir forge_build
          cd forge_build

          curl -fL -o forge-mdk.zip \
            https://maven.minecraftforge.net/net/minecraftforge/forge/1.20.1-47.2.20/forge-1.20.1-47.2.20-mdk.zip
          unzip -q forge-mdk.zip
          rm -rf src/main/java/*

          mkdir -p src/main/java/com/cybermod/olympustrial
          cp ../OlympusTrialMod.java src/main/java/com/cybermod/olympustrial/OlympusTrialMod.java

          sed -i 's/^mod_id=.*/mod_id=olympus_trial/' gradle.properties
          sed -i 's/^mod_name=.*/mod_name=God of War 2 - 3D Mod/' gradle.properties
          sed -i 's/^mod_group_id=.*/mod_group_id=com.cybermod.olympustrial/' gradle.properties
          sed -i 's/^mod_version=.*/mod_version=2.0.0/' gradle.properties

          mkdir -p src/main/resources/assets/olympus_trial/models/item
          mkdir -p src/main/resources/assets/olympus_trial/textures/item
          mkdir -p src/main/resources/assets/olympus_trial/lang

          # توليد خامات (Textures) مخصصة عالية الجودة بالبايثون بدلاً من صور ماين كرافت!
          python3 - << 'PYEOF'
          import zlib, struct

          def save_png(path, w, h, pixel_fn):
              raw = bytearray()
              for y in range(h):
                  raw.append(0)
                  for x in range(w):
                      r, g, b, a = pixel_fn(x, y, w, h)
                      raw.extend((max(0, min(255, int(r))), max(0, min(255, int(g))), max(0, min(255, int(b))), max(0, min(255, int(a)))))
              def chunk(tag, data):
                  return struct.pack("!I", len(data)) + tag + data + struct.pack("!I", zlib.crc32(tag + data) & 0xffffffff)
              ihdr = struct.pack("!IIBBBBB", w, h, 8, 6, 0, 0, 0)
              png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + chunk(b"IEND", b"")
              with open(path, "wb") as f:
                  f.write(png)

          base = "src/main/resources/assets/olympus_trial/textures/item/"

          # 1. خامة نصل أثينا الناري المتوهج
          save_png(base + "athena_blade.png", 32, 32, lambda x, y, w, h: (
              255, 80 + (y * 5) % 170, (x * 8) % 90, 255
          ) if (x + y) % 5 != 0 else (255, 230, 120, 255))

          # 2. خامة الذهب الإغريقي للمقبض والدرع
          save_png(base + "greek_gold.png", 32, 32, lambda x, y, w, h: (
              235 - ((x ^ y) % 40), 175 - ((x ^ y) % 30), 30, 255
          ))

          # 3. خامة الحديد الأسود للسلاسل
          save_png(base + "dark_chain.png", 32, 32, lambda x, y, w, h: (
              70 + (x * y) % 35, 75 + (x * y) % 35, 85 + (x * y) % 35, 255
          ))

          # 4. خامة حراشف وثعابين ميدوسا الزمردية
          save_png(base + "medusa_scales.png", 32, 32, lambda x, y, w, h: (
              25, 160 + ((x * 7 + y * 5) % 85), 55, 255
          ))

          # 5. خامة عيون ميدوسا المشعة
          save_png(base + "medusa_glow.png", 32, 32, lambda x, y, w, h: (
              120, 255, 140, 255
          ))

          # 6. خامة برق كرونوس الأزرق
          save_png(base + "cronos_lightning.png", 32, 32, lambda x, y, w, h: (
              20, 200 + (x * 9) % 55, 255, 255
          ))

          # 7. خامة صخور وحمم أطلس البركانية
          save_png(base + "atlas_magma.png", 32, 32, lambda x, y, w, h: (
              255, 110, 10, 255
          ) if (x * x + y * y) % 7 == 0 else (65, 50, 50, 255))
          PYEOF

          # ================= 1. المجسم ثلاثي الأبعاد: BLADES OF ATHENA 3D =================
          cat > src/main/resources/assets/olympus_trial/models/item/blades_of_athena.json <<'EOF'
          {
            "credit": "Custom 3D God of War 2 Model",
            "textures": {
              "blade": "olympus_trial:item/athena_blade",
              "gold": "olympus_trial:item/greek_gold",
              "chain": "olympus_trial:item/dark_chain",
              "particle": "olympus_trial:item/athena_blade"
            },
            "elements": [
              {
                "name": "handle",
                "from": [7.2, 1, 7.2],
                "to": [8.8, 6.5, 8.8],
                "faces": {
                  "north": {"uv": [0, 0, 4, 12], "texture": "#chain"},
                  "east": {"uv": [0, 0, 4, 12], "texture": "#chain"},
                  "south": {"uv": [0, 0, 4, 12], "texture": "#chain"},
                  "west": {"uv": [0, 0, 4, 12], "texture": "#chain"},
                  "up": {"uv": [0, 0, 4, 4], "texture": "#gold"},
                  "down": {"uv": [0, 0, 4, 4], "texture": "#gold"}
                }
              },
              {
                "name": "omega_guard",
                "from": [4.5, 6.2, 6.5],
                "to": [11.5, 8.2, 9.5],
                "faces": {
                  "north": {"uv": [0, 0, 14, 4], "texture": "#gold"},
                  "east": {"uv": [0, 0, 6, 4], "texture": "#gold"},
                  "south": {"uv": [0, 0, 14, 4], "texture": "#gold"},
                  "west": {"uv": [0, 0, 6, 4], "texture": "#gold"},
                  "up": {"uv": [0, 0, 14, 6], "texture": "#gold"},
                  "down": {"uv": [0, 0, 14, 6], "texture": "#gold"}
                }
              },
              {
                "name": "main_war_blade",
                "from": [5.5, 8.0, 7.3],
                "to": [10.8, 22.0, 8.7],
                "faces": {
                  "north": {"uv": [0, 0, 12, 16], "texture": "#blade"},
                  "east": {"uv": [0, 0, 3, 16], "texture": "#gold"},
                  "south": {"uv": [0, 0, 12, 16], "texture": "#blade"},
                  "west": {"uv": [0, 0, 3, 16], "texture": "#blade"},
                  "up": {"uv": [0, 0, 10, 3], "texture": "#blade"},
                  "down": {"uv": [0, 0, 10, 3], "texture": "#gold"}
                }
              },
              {
                "name": "curved_fang_tip",
                "from": [4.0, 17.5, 7.4],
                "to": [11.8, 25.0, 8.6],
                "rotation": {"angle": -22.5, "axis": "z", "origin": [8, 20, 8]},
                "faces": {
                  "north": {"uv": [0, 0, 14, 14], "texture": "#blade"},
                  "east": {"uv": [0, 0, 2, 14], "texture": "#blade"},
                  "south": {"uv": [0, 0, 14, 14], "texture": "#blade"},
                  "west": {"uv": [0, 0, 2, 14], "texture": "#blade"},
                  "up": {"uv": [0, 0, 14, 2], "texture": "#blade"},
                  "down": {"uv": [0, 0, 14, 2], "texture": "#blade"}
                }
              },
              {
                "name": "blade_hook_barb",
                "from": [3.5, 12.0, 7.4],
                "to": [6.5, 16.0, 8.6],
                "rotation": {"angle": 22.5, "axis": "z", "origin": [5, 14, 8]},
                "faces": {
                  "north": {"uv": [0, 0, 6, 8], "texture": "#gold"},
                  "east": {"uv": [0, 0, 2, 8], "texture": "#gold"},
                  "south": {"uv": [0, 0, 6, 8], "texture": "#gold"},
                  "west": {"uv": [0, 0, 2, 8], "texture": "#gold"},
                  "up": {"uv": [0, 0, 6, 2], "texture": "#gold"},
                  "down": {"uv": [0, 0, 6, 2], "texture": "#gold"}
                }
              },
              {
                "name": "hanging_chain_1",
                "from": [7.5, -3.5, 7.5],
                "to": [8.5, 1.0, 8.5],
                "rotation": {"angle": 22.5, "axis": "x", "origin": [8, 1, 8]},
                "faces": {
                  "north": {"uv": [0, 0, 3, 10], "texture": "#chain"},
                  "east": {"uv": [0, 0, 3, 10], "texture": "#chain"},
                  "south": {"uv": [0, 0, 3, 10], "texture": "#chain"},
                  "west": {"uv": [0, 0, 3, 10], "texture": "#chain"},
                  "up": {"uv": [0, 0, 3, 3], "texture": "#chain"},
                  "down": {"uv": [0, 0, 3, 3], "texture": "#chain"}
                }
              }
            ],
            "display": {
              "thirdperson_righthand": {
                "rotation": [0, -90, 45],
                "translation": [0, 3.5, 1.0],
                "scale": [1.35, 1.35, 1.1]
              },
              "thirdperson_lefthand": {
                "rotation": [0, 90, -45],
                "translation": [0, 3.5, 1.0],
                "scale": [1.35, 1.35, 1.1]
              },
              "firstperson_righthand": {
                "rotation": [0, -90, 25],
                "translation": [1.2, 2.5, 0.5],
                "scale": [1.1, 1.1, 1.0]
              },
              "firstperson_lefthand": {
                "rotation": [0, 90, -25],
                "translation": [1.2, 2.5, 0.5],
                "scale": [1.1, 1.1, 1.0]
              },
              "gui": {
                "rotation": [25, -35, -45],
                "translation": [0, -2.5, 0],
                "scale": [0.72, 0.72, 0.72]
              }
            }
          }
          EOF

          # ================= 2. المجسم ثلاثي الأبعاد: MEDUSA HEAD 3D =================
          cat > src/main/resources/assets/olympus_trial/models/item/medusa_head.json <<'EOF'
          {
            "textures": {
              "skin": "olympus_trial:item/medusa_scales",
              "eyes": "olympus_trial:item/medusa_glow",
              "particle": "olympus_trial:item/medusa_scales"
            },
            "elements": [
              {
                "name": "gorgon_head",
                "from": [4, 4, 4],
                "to": [12, 12, 12],
                "faces": {
                  "north": {"uv": [0, 0, 16, 16], "texture": "#skin"},
                  "east": {"uv": [0, 0, 16, 16], "texture": "#skin"},
                  "south": {"uv": [0, 0, 16, 16], "texture": "#skin"},
                  "west": {"uv": [0, 0, 16, 16], "texture": "#skin"},
                  "up": {"uv": [0, 0, 16, 16], "texture": "#skin"},
                  "down": {"uv": [0, 0, 16, 16], "texture": "#skin"}
                }
              },
              {
                "name": "glowing_eyes",
                "from": [5, 7, 3.7],
                "to": [11, 9, 4.2],
                "faces": {
                  "north": {"uv": [0, 0, 12, 4], "texture": "#eyes"},
                  "south": {"uv": [0, 0, 12, 4], "texture": "#eyes"},
                  "up": {"uv": [0, 0, 12, 2], "texture": "#eyes"},
                  "down": {"uv": [0, 0, 12, 2], "texture": "#eyes"}
                }
              },
              {
                "name": "snake_1",
                "from": [3, 11, 5],
                "to": [5, 17, 7],
                "rotation": {"angle": 22.5, "axis": "z", "origin": [4, 11, 6]},
                "faces": {
                  "north": {"uv": [0, 0, 4, 12], "texture": "#skin"},
                  "east": {"uv": [0, 0, 4, 12], "texture": "#skin"},
                  "south": {"uv": [0, 0, 4, 12], "texture": "#skin"},
                  "west": {"uv": [0, 0, 4, 12], "texture": "#skin"},
                  "up": {"uv": [0, 0, 4, 4], "texture": "#eyes"}
                }
              },
              {
                "name": "snake_2",
                "from": [11, 11, 5],
                "to": [13, 17, 7],
                "rotation": {"angle": -22.5, "axis": "z", "origin": [12, 11, 6]},
                "faces": {
                  "north": {"uv": [0, 0, 4, 12], "texture": "#skin"},
                  "east": {"uv": [0, 0, 4, 12], "texture": "#skin"},
                  "south": {"uv": [0, 0, 4, 12], "texture": "#skin"},
                  "west": {"uv": [0, 0, 4, 12], "texture": "#skin"},
                  "up": {"uv": [0, 0, 4, 4], "texture": "#eyes"}
                }
              },
              {
                "name": "snake_3",
                "from": [7, 11, 9],
                "to": [9, 18, 11],
                "rotation": {"angle": -22.5, "axis": "x", "origin": [8, 11, 10]},
                "faces": {
                  "north": {"uv": [0, 0, 4, 12], "texture": "#skin"},
                  "east": {"uv": [0, 0, 4, 12], "texture": "#skin"},
                  "south": {"uv": [0, 0, 4, 12], "texture": "#skin"},
                  "west": {"uv": [0, 0, 4, 12], "texture": "#skin"},
                  "up": {"uv": [0, 0, 4, 4], "texture": "#eyes"}
                }
              }
            ],
            "display": {
              "thirdperson_righthand": {"rotation": [10, 180, 0], "translation": [0, 2, 1], "scale": [0.9, 0.9, 0.9]},
              "firstperson_righthand": {"rotation": [0, 160, 0], "translation": [1, 2, 0], "scale": [0.85, 0.85, 0.85]},
              "gui": {"rotation": [25, 145, 0], "translation": [0, -1, 0], "scale": [0.85, 0.85, 0.85]}
            }
          }
          EOF

          # ================= 3. المجسم ثلاثي الأبعاد: BOW OF DESTINY 3D =================
          cat > src/main/resources/assets/olympus_trial/models/item/bow_of_destiny.json <<'EOF'
          {
            "textures": {
              "gold": "olympus_trial:item/greek_gold",
              "energy": "olympus_trial:item/cronos_lightning",
              "particle": "olympus_trial:item/greek_gold"
            },
            "elements": [
              {
                "name": "bow_grip",
                "from": [6.5, 5, 7],
                "to": [9.5, 11, 9],
                "faces": {
                  "north": {"uv": [0, 0, 6, 12], "texture": "#gold"},
                  "east": {"uv": [0, 0, 4, 12], "texture": "#gold"},
                  "south": {"uv": [0, 0, 6, 12], "texture": "#gold"},
                  "west": {"uv": [0, 0, 4, 12], "texture": "#gold"}
                }
              },
              {
                "name": "upper_limb",
                "from": [7, 10, 7],
                "to": [9, 19, 9],
                "rotation": {"angle": 22.5, "axis": "z", "origin": [8, 10, 8]},
                "faces": {
                  "north": {"uv": [0, 0, 4, 16], "texture": "#gold"},
                  "east": {"uv": [0, 0, 4, 16], "texture": "#gold"},
                  "south": {"uv": [0, 0, 4, 16], "texture": "#gold"},
                  "west": {"uv": [0, 0, 4, 16], "texture": "#gold"}
                }
              },
              {
                "name": "lower_limb",
                "from": [7, -3, 7],
                "to": [9, 6, 9],
                "rotation": {"angle": -22.5, "axis": "z", "origin": [8, 6, 8]},
                "faces": {
                  "north": {"uv": [0, 0, 4, 16], "texture": "#gold"},
                  "east": {"uv": [0, 0, 4, 16], "texture": "#gold"},
                  "south": {"uv": [0, 0, 4, 16], "texture": "#gold"},
                  "west": {"uv": [0, 0, 4, 16], "texture": "#gold"}
                }
              },
              {
                "name": "laser_string",
                "from": [10.5, -2, 7.6],
                "to": [11.3, 18, 8.4],
                "faces": {
                  "north": {"uv": [0, 0, 2, 16], "texture": "#energy"},
                  "east": {"uv": [0, 0, 2, 16], "texture": "#energy"},
                  "south": {"uv": [0, 0, 2, 16], "texture": "#energy"},
                  "west": {"uv": [0, 0, 2, 16], "texture": "#energy"}
                }
              }
            ],
            "display": {
              "thirdperson_righthand": {"rotation": [0, -90, 0], "translation": [0, 2, 1], "scale": [1.2, 1.2, 1.2]},
              "firstperson_righthand": {"rotation": [0, -90, 15], "translation": [1.5, 2, 0], "scale": [1.0, 1.0, 1.0]},
              "gui": {"rotation": [20, -35, -35], "scale": [0.75, 0.75, 0.75]}
            }
          }
          EOF

          # ================= 4. المجسم ثلاثي الأبعاد: CRONOS POWER ORB 3D =================
          cat > src/main/resources/assets/olympus_trial/models/item/cronos_power.json <<'EOF'
          {
            "textures": {
              "core": "olympus_trial:item/cronos_lightning",
              "ring": "olympus_trial:item/greek_gold",
              "particle": "olympus_trial:item/cronos_lightning"
            },
            "elements": [
              {
                "name": "lightning_core",
                "from": [5, 5, 5],
                "to": [11, 11, 11],
                "faces": {
                  "north": {"uv": [0, 0, 12, 12], "texture": "#core"},
                  "east": {"uv": [0, 0, 12, 12], "texture": "#core"},
                  "south": {"uv": [0, 0, 12, 12], "texture": "#core"},
                  "west": {"uv": [0, 0, 12, 12], "texture": "#core"},
                  "up": {"uv": [0, 0, 12, 12], "texture": "#core"},
                  "down": {"uv": [0, 0, 12, 12], "texture": "#core"}
                }
              },
              {
                "name": "cage_ring_1",
                "from": [3, 7, 3],
                "to": [13, 9, 13],
                "rotation": {"angle": 45, "axis": "z", "origin": [8, 8, 8]},
                "faces": {
                  "north": {"uv": [0, 0, 16, 4], "texture": "#ring"},
                  "east": {"uv": [0, 0, 16, 4], "texture": "#ring"},
                  "south": {"uv": [0, 0, 16, 4], "texture": "#ring"},
                  "west": {"uv": [0, 0, 16, 4], "texture": "#ring"}
                }
              },
              {
                "name": "cage_ring_2",
                "from": [3, 7, 3],
                "to": [13, 9, 13],
                "rotation": {"angle": -45, "axis": "x", "origin": [8, 8, 8]},
                "faces": {
                  "north": {"uv": [0, 0, 16, 4], "texture": "#ring"},
                  "east": {"uv": [0, 0, 16, 4], "texture": "#ring"},
                  "south": {"uv": [0, 0, 16, 4], "texture": "#ring"},
                  "west": {"uv": [0, 0, 16, 4], "texture": "#ring"}
                }
              }
            ],
            "display": {
              "thirdperson_righthand": {"rotation": [20, 45, 0], "translation": [0, 2, 0], "scale": [0.9, 0.9, 0.9]},
              "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [1, 2.5, 0], "scale": [0.85, 0.85, 0.85]},
              "gui": {"rotation": [30, 45, 0], "scale": [0.95, 0.95, 0.95]}
            }
          }
          EOF

          # ================= 5. المجسم ثلاثي الأبعاد: ATLAS QUAKE GAUNTLET 3D =================
          cat > src/main/resources/assets/olympus_trial/models/item/atlas_quake.json <<'EOF'
          {
            "textures": {
              "rock": "olympus_trial:item/atlas_magma",
              "lava": "olympus_trial:item/athena_blade",
              "particle": "olympus_trial:item/atlas_magma"
            },
            "elements": [
              {
                "name": "titan_fist",
                "from": [3, 3, 3],
                "to": [13, 13, 13],
                "faces": {
                  "north": {"uv": [0, 0, 16, 16], "texture": "#rock"},
                  "east": {"uv": [0, 0, 16, 16], "texture": "#rock"},
                  "south": {"uv": [0, 0, 16, 16], "texture": "#rock"},
                  "west": {"uv": [0, 0, 16, 16], "texture": "#rock"},
                  "up": {"uv": [0, 0, 16, 16], "texture": "#lava"},
                  "down": {"uv": [0, 0, 16, 16], "texture": "#rock"}
                }
              },
              {
                "name": "magma_spike_1",
                "from": [2, 6, 6],
                "to": [14, 10, 10],
                "rotation": {"angle": 22.5, "axis": "y", "origin": [8, 8, 8]},
                "faces": {
                  "north": {"uv": [0, 0, 16, 8], "texture": "#lava"},
                  "east": {"uv": [0, 0, 8, 8], "texture": "#lava"},
                  "south": {"uv": [0, 0, 16, 8], "texture": "#lava"},
                  "west": {"uv": [0, 0, 8, 8], "texture": "#lava"}
                }
              }
            ],
            "display": {
              "thirdperson_righthand": {"rotation": [0, 45, 0], "translation": [0, 1.5, 0], "scale": [1.1, 1.1, 1.1]},
              "firstperson_righthand": {"rotation": [15, 45, 0], "translation": [1, 1.5, 0], "scale": [0.95, 0.95, 0.95]},
              "gui": {"rotation": [30, 45, 0], "scale": [0.85, 0.85, 0.85]}
            }
          }
          EOF

          cat > src/main/resources/assets/olympus_trial/lang/en_us.json <<'EOF'
          {
            "item.olympus_trial.blades_of_athena": "§6§lBlades of Athena ⚔",
            "item.olympus_trial.cronos_power": "§b§lCronos' Rage ⚡",
            "item.olympus_trial.bow_of_destiny": "§e§lBow of Destiny 🏹",
            "item.olympus_trial.medusa_head": "§a§lHead of Medusa 🐍",
            "item.olympus_trial.atlas_quake": "§c§lAtlas Quake 🌋"
          }
          EOF

      - name: Build mod JAR
        shell: bash
        run: |
          set -euo pipefail
          cd forge_build
          chmod +x gradlew
          ./gradlew build --no-daemon

      - name: Upload mod
        uses: actions/upload-artifact@v4
        with:
          name: GodOfWar2-3D-Mod-1.20.1
          path: forge_build/build/libs/*.jar
          if-no-files-found: error
```

---
