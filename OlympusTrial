package com.cybermod.olympustrial;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
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
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.TickEvent;
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
    private static final String PROTOCOL = "1";
    private static final int MAX_MAGIC = 100;

    private static final String MAGIC_KEY = "OlympusMagic";
    private static final String INITIALIZED_KEY = "OlympusInitialized";
    private static final String STARTER_KEY = "OlympusStarterGiven";

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
        IEventBus modBus =
                FMLJavaModLoadingContext.get().getModEventBus();

        ITEMS.register(modBus);
        modBus.addListener(this::addCreativeItems);

        CHANNEL.messageBuilder(
                        MagicPacket.class,
                        0,
                        NetworkDirection.PLAY_TO_CLIENT
                )
                .encoder((message, buffer) ->
                        buffer.writeInt(message.magic))
                .decoder(MagicPacket::decode)
                .consumerMainThread((message, context) -> {
                    ClientMagic.value = message.magic;
                    context.get().setPacketHandled(true);
                })
                .add();

        MinecraftForge.EVENT_BUS.register(ForgeEvents.class);
    }

    private void addCreativeItems(
            BuildCreativeModeTabContentsEvent event
    ) {
        if (event.getTabKey() == CreativeModeTabs.COMBAT) {
            event.accept(BLADES);
            event.accept(CRONOS);
            event.accept(BOW);
            event.accept(MEDUSA);
            event.accept(ATLAS);
        }
    }

    private enum Power {
        CRONOS,
        BOW,
        MEDUSA,
        ATLAS
    }

    /*
     * بيانات اللاعب المحفوظة بين مرات الدخول.
     */
    private static CompoundTag playerData(Player player) {
        CompoundTag root = player.getPersistentData();

        if (!root.contains(Player.PERSISTED_NBT_TAG)) {
            root.put(
                    Player.PERSISTED_NBT_TAG,
                    new CompoundTag()
            );
        }

        return root.getCompound(Player.PERSISTED_NBT_TAG);
    }

    private static int getMagic(Player player) {
        CompoundTag data = playerData(player);

        if (!data.getBoolean(INITIALIZED_KEY)) {
            data.putBoolean(INITIALIZED_KEY, true);
            data.putInt(MAGIC_KEY, MAX_MAGIC);
        }

        return Math.max(
                0,
                Math.min(MAX_MAGIC, data.getInt(MAGIC_KEY))
        );
    }

    private static void setMagic(Player player, int amount) {
        playerData(player).putInt(
                MAGIC_KEY,
                Math.max(0, Math.min(MAX_MAGIC, amount))
        );

        syncMagic(player);
    }

    private static void syncMagic(Player player) {
        if (player instanceof ServerPlayer serverPlayer) {
            CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> serverPlayer),
                    new MagicPacket(getMagic(player))
            );
        }
    }

    private static boolean spendMagic(
            Player player,
            int amount
    ) {
        int current = getMagic(player);

        if (current < amount) {
            player.displayClientMessage(
                    net.minecraft.network.chat.Component.literal(
                            "Not enough magic!"
                    ),
                    true
            );

            player.level().playSound(
                    null,
                    player.blockPosition(),
                    SoundEvents.NOTE_BLOCK_BASS.value(),
                    SoundSource.PLAYERS,
                    0.8F,
                    0.7F
            );

            return false;
        }

        setMagic(player, current - amount);
        return true;
    }

    private static List<LivingEntity> nearbyTargets(
            Player player,
            double radius
    ) {
        AABB area = player.getBoundingBox().inflate(radius);

        return player.level().getEntitiesOfClass(
                LivingEntity.class,
                area,
                target -> target != player
                        && target.isAlive()
                        && player.distanceToSqr(target) <= radius * radius
        );
    }

    private static void giveStarterItem(
            Player player,
            Item item
    ) {
        ItemStack stack = new ItemStack(item);

        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
    }

    @Mod.EventBusSubscriber(
            modid = MOD_ID,
            bus = Mod.EventBusSubscriber.Bus.FORGE
    )
    public static class ForgeEvents {

        @SubscribeEvent
        public static void onLogin(
                PlayerEvent.PlayerLoggedInEvent event
        ) {
            Player player = event.getEntity();

            if (player.level().isClientSide) {
                return;
            }

            CompoundTag data = playerData(player);

            if (!data.getBoolean(STARTER_KEY)) {
                data.putBoolean(STARTER_KEY, true);

                giveStarterItem(player, BLADES.get());
                giveStarterItem(player, CRONOS.get());
                giveStarterItem(player, BOW.get());
                giveStarterItem(player, MEDUSA.get());
                giveStarterItem(player, ATLAS.get());
            }

            syncMagic(player);
        }

        @SubscribeEvent
        public static void onPlayerTick(
                TickEvent.PlayerTickEvent event
        ) {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }

            Player player = event.player;

            if (player.level().isClientSide) {
                return;
            }

            // نقطتان Magic كل ثانية.
            if (player.tickCount % 20 == 0) {
                int current = getMagic(player);

                if (current < MAX_MAGIC) {
                    setMagic(player, current + 2);
                } else {
                    syncMagic(player);
                }
            }
        }
    }

    /*
     * سلاح Blades: سيف عادي في الضربة اليسرى،
     * وضربة دائرية عند الكليك اليمين.
     */
    public static class BladesItem extends SwordItem {
        public BladesItem() {
            super(
                    Tiers.DIAMOND,
                    5,
                    -2.0F,
                    new Item.Properties()
                            .rarity(Rarity.EPIC)
                            .fireResistant()
            );
        }

        @Override
        public boolean isFoil(ItemStack stack) {
            return true;
        }

        @Override
        public InteractionResultHolder<ItemStack> use(
                Level level,
                Player player,
                InteractionHand hand
        ) {
            ItemStack stack = player.getItemInHand(hand);

            if (!level.isClientSide
                    && level instanceof ServerLevel serverLevel) {

                if (!spendMagic(player, 12)) {
                    return InteractionResultHolder.fail(stack);
                }

                for (LivingEntity target :
                        nearbyTargets(player, 5.0)) {
                    target.hurt(
                            level.damageSources().playerAttack(player),
                            8.0F
                    );

                    target.knockback(
                            0.8,
                            player.getX() - target.getX(),
                            player.getZ() - target.getZ()
                    );

                    target.setSecondsOnFire(3);
                }

                serverLevel.sendParticles(
                        ParticleTypes.FLAME,
                        player.getX(),
                        player.getY() + 1.0,
                        player.getZ(),
                        65,
                        2.5,
                        0.7,
                        2.5,
                        0.08
                );

                serverLevel.playSound(
                        null,
                        player.blockPosition(),
                        SoundEvents.BLAZE_SHOOT,
                        SoundSource.PLAYERS,
                        1.2F,
                        0.8F
                );

                player.getCooldowns().addCooldown(this, 35);
            }

            return InteractionResultHolder.sidedSuccess(
                    stack,
                    level.isClientSide
            );
        }
    }

    public static class PowerItem extends Item {
        private final Power power;

        public PowerItem(Power power) {
            super(
                    new Item.Properties()
                            .stacksTo(1)
                            .rarity(Rarity.EPIC)
            );

            this.power = power;
        }

        @Override
        public boolean isFoil(ItemStack stack) {
            return true;
        }

        @Override
        public InteractionResultHolder<ItemStack> use(
                Level level,
                Player player,
                InteractionHand hand
        ) {
            ItemStack stack = player.getItemInHand(hand);

            if (level.isClientSide) {
                return InteractionResultHolder.sidedSuccess(
                        stack,
                        true
                );
            }

            if (!(level instanceof ServerLevel serverLevel)) {
                return InteractionResultHolder.fail(stack);
            }

            int cost = switch (power) {
                case CRONOS -> 30;
                case BOW -> 8;
                case MEDUSA -> 25;
                case ATLAS -> 35;
            };

            if (!spendMagic(player, cost)) {
                return InteractionResultHolder.fail(stack);
            }

            switch (power) {
                case CRONOS -> useCronos(serverLevel, player);
                case BOW -> useBow(serverLevel, player);
                case MEDUSA -> useMedusa(serverLevel, player);
                case ATLAS -> useAtlas(serverLevel, player);
            }

            int cooldown = switch (power) {
                case CRONOS -> 100;
                case BOW -> 12;
                case MEDUSA -> 120;
                case ATLAS -> 140;
            };

            player.getCooldowns().addCooldown(this, cooldown);

            return InteractionResultHolder.sidedSuccess(
                    stack,
                    false
            );
        }
    }

    private static void useCronos(
            ServerLevel level,
            Player player
    ) {
        List<LivingEntity> targets = nearbyTargets(player, 12.0);

        int strikes = 0;

        for (LivingEntity target : targets) {
            if (strikes >= 5) {
                break;
            }

            LightningBolt bolt =
                    EntityType.LIGHTNING_BOLT.create(level);

            if (bolt != null) {
                bolt.moveTo(target.position());
                level.addFreshEntity(bolt);
                strikes++;
            }
        }

        // مؤثر يظهر حتى لو لم يكن هناك عدو قريب.
        level.sendParticles(
                ParticleTypes.ELECTRIC_SPARK,
                player.getX(),
                player.getY() + 1.0,
                player.getZ(),
                70,
                2.0,
                1.0,
                2.0,
                0.15
        );

        level.playSound(
                null,
                player.blockPosition(),
                SoundEvents.TRIDENT_THUNDER,
                SoundSource.PLAYERS,
                1.0F,
                1.2F
        );
    }

    private static void useBow(
            ServerLevel level,
            Player player
    ) {
        Arrow arrow = new Arrow(level, player);

        arrow.shootFromRotation(
                player,
                player.getXRot(),
                player.getYRot(),
                0.0F,
                3.0F,
                0.5F
        );

        arrow.setBaseDamage(7.0D);
        arrow.setCritArrow(true);
        arrow.pickup = AbstractArrow.Pickup.CREATIVE_ONLY;

        level.addFreshEntity(arrow);

        level.playSound(
                null,
                player.blockPosition(),
                SoundEvents.ARROW_SHOOT,
                SoundSource.PLAYERS,
                1.0F,
                1.0F
        );
    }

    private static void useMedusa(
            ServerLevel level,
            Player player
    ) {
        for (LivingEntity target :
                nearbyTargets(player, 8.0)) {

            target.addEffect(
                    new MobEffectInstance(
                            MobEffects.MOVEMENT_SLOWDOWN,
                            120,
                            5
                    )
            );

            target.addEffect(
                    new MobEffectInstance(
                            MobEffects.WEAKNESS,
                            120,
                            1
                    )
            );

            level.sendParticles(
                    ParticleTypes.HAPPY_VILLAGER,
                    target.getX(),
                    target.getY() + target.getBbHeight() / 2.0,
                    target.getZ(),
                    20,
                    0.4,
                    0.7,
                    0.4,
                    0.03
            );
        }

        level.playSound(
                null,
                player.blockPosition(),
                SoundEvents.EVOKER_CAST_SPELL,
                SoundSource.PLAYERS,
                1.0F,
                0.7F
        );
    }

    private static void useAtlas(
            ServerLevel level,
            Player player
    ) {
        for (LivingEntity target :
                nearbyTargets(player, 10.0)) {

            target.hurt(
                    level.damageSources().playerAttack(player),
                    10.0F
            );

            double dx = target.getX() - player.getX();
            double dz = target.getZ() - player.getZ();
            double length = Math.max(
                    0.1,
                    Math.sqrt(dx * dx + dz * dz)
            );

            target.setDeltaMovement(
                    target.getDeltaMovement().add(
                            dx / length * 0.9,
                            0.8,
                            dz / length * 0.9
                    )
            );

            target.hurtMarked = true;
        }

        level.sendParticles(
                ParticleTypes.CLOUD,
                player.getX(),
                player.getY() + 0.2,
                player.getZ(),
                100,
                4.0,
                0.3,
                4.0,
                0.12
        );

        level.sendParticles(
                ParticleTypes.EXPLOSION,
                player.getX(),
                player.getY() + 0.4,
                player.getZ(),
                12,
                3.0,
                0.3,
                3.0,
                0.0
        );

        level.playSound(
                null,
                player.blockPosition(),
                SoundEvents.GENERIC_EXPLODE,
                SoundSource.PLAYERS,
                1.4F,
                0.65F
        );
    }

    /*
     * إرسال قيمة الـMagic من السيرفر إلى شاشة اللاعب.
     */
    public static class MagicPacket {
        private final int magic;

        public MagicPacket(int magic) {
            this.magic = magic;
        }

        public static MagicPacket decode(
                FriendlyByteBuf buffer
        ) {
            return new MagicPacket(buffer.readInt());
        }
    }

    public static class ClientMagic {
        public static int value = MAX_MAGIC;
    }

    /*
     * واجهة العميل: صحة + Magic أعلى اليسار.
     */
    @Mod.EventBusSubscriber(
            modid = MOD_ID,
            bus = Mod.EventBusSubscriber.Bus.MOD,
            value = Dist.CLIENT
    )
    public static class ClientEvents {

        @SubscribeEvent
        public static void registerOverlays(
                RegisterGuiOverlaysEvent event
        ) {
            event.registerAboveAll(
                    "olympus_bars",
                    (gui, graphics, partialTick, width, height) -> {
                        var minecraft =
                                net.minecraft.client.Minecraft.getInstance();

                        Player player = minecraft.player;

                        if (player == null
                                || minecraft.options.hideGui) {
                            return;
                        }

                        int x = 10;
                        int y = 10;
                        int barWidth = 120;
                        int barHeight = 9;

                        float healthRatio = Math.max(
                                0.0F,
                                Math.min(
                                        1.0F,
                                        player.getHealth()
                                                / player.getMaxHealth()
                                )
                        );

                        float magicRatio = Math.max(
                                0.0F,
                                Math.min(
                                        1.0F,
                                        ClientMagic.value
                                                / (float) MAX_MAGIC
                                )
                        );

                        // خلفية شريط الصحة.
                        graphics.fill(
                                x,
                                y,
                                x + barWidth,
                                y + barHeight,
                                0xCC220000
                        );

                        // شريط الصحة الأحمر.
                        graphics.fill(
                                x,
                                y,
                                x + (int) (barWidth * healthRatio),
                                y + barHeight,
                                0xFFDD3333
                        );

                        graphics.drawString(
                                minecraft.font,
                                "HEALTH",
                                x + 3,
                                y + 1,
                                0xFFFFFFFF,
                                false
                        );

                        int magicY = y + 14;

                        // خلفية شريط السحر.
                        graphics.fill(
                                x,
                                magicY,
                                x + barWidth,
                                magicY + barHeight,
                                0xCC001122
                        );

                        // شريط السحر الأزرق.
                        graphics.fill(
                                x,
                                magicY,
                                x + (int) (barWidth * magicRatio),
                                magicY + barHeight,
                                0xFF2299FF
                        );

                        graphics.drawString(
                                minecraft.font,
                                "MAGIC " + ClientMagic.value + "/100",
                                x + 3,
                                magicY + 1,
                                0xFFFFFFFF,
                                false
                        );
                    }
            );
        }
    }
}
