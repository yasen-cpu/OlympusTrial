import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;

@Mod(OlympusTrialMod.MODID)
public class OlympusTrialMod {
    public static final String MODID = "olympus_trial";
    private static final String PROTOCOL = "3";

    public static final SimpleChannel NETWORK = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(MODID, "main"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals
    );

    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MODID);

    public static final RegistryObject<Item> BLADES = ITEMS.register("blades_of_athena",
            () -> new BladesItem(new Item.Properties().stacksTo(1).rarity(Rarity.EPIC).fireResistant()));

    public static final RegistryObject<Item> CRONOS = ITEMS.register("cronos_power",
            () -> new GodRelicItem(new Item.Properties().stacksTo(1).rarity(Rarity.EPIC).fireResistant(), RelicType.CRONOS));

    public static final RegistryObject<Item> BOW = ITEMS.register("bow_of_destiny",
            () -> new GodRelicItem(new Item.Properties().stacksTo(1).rarity(Rarity.RARE).fireResistant(), RelicType.BOW));

    public static final RegistryObject<Item> MEDUSA = ITEMS.register("medusa_head",
            () -> new GodRelicItem(new Item.Properties().stacksTo(1).rarity(Rarity.EPIC).fireResistant(), RelicType.MEDUSA));

    public static final RegistryObject<Item> ATLAS = ITEMS.register("atlas_quake",
            () -> new GodRelicItem(new Item.Properties().stacksTo(1).rarity(Rarity.EPIC).fireResistant(), RelicType.ATLAS));

    private static final String TAG_MAGIC = "OlympusMagic";
    private static final String TAG_ORBS = "OlympusRedOrbs";
    private static final String TAG_INIT = "OlympusInitialized";
    private static final String TAG_STARTER = "OlympusStarterGivenV3";
    private static final String TAG_PETRIFIED = "OlympusPetrifiedTicks";
    private static final String TAG_OFFHAND_CLONE = "OlympusOffhandClone";
    private static final int MAX_MAGIC = 100;

    public enum RelicType {
        CRONOS, BOW, MEDUSA, ATLAS
    }

    public OlympusTrialMod() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        ITEMS.register(bus);
        bus.addListener(this::addCreativeItems);

        NETWORK.registerMessage(0, HudSyncPacket.class, HudSyncPacket::encode, HudSyncPacket::decode, HudSyncPacket::handle);
        MinecraftForge.EVENT_BUS.register(this);
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

    // ==================== 3D MODEL OVERRIDE ON MOD BUS ====================
    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientModBusEvents {
        @SubscribeEvent
        public static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
            Map<ResourceLocation, BakedModel> models = event.getModels();
            for (Map.Entry<ResourceLocation, BakedModel> entry : models.entrySet()) {
                ResourceLocation key = entry.getKey();
                if (MODID.equals(key.getNamespace())) {
                    entry.setValue(new OlympusCustomBakedModel(entry.getValue()));
                }
            }
        }
    }

    public static class OlympusCustomBakedModel implements BakedModel {
        private final BakedModel original;

        public OlympusCustomBakedModel(BakedModel original) {
            this.original = original;
        }

        @Override
        public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource rand) {
            return Collections.emptyList();
        }

        @Override
        public boolean useAmbientOcclusion() {
            return true;
        }

        @Override
        public boolean isGui3d() {
            return true;
        }

        @Override
        public boolean usesBlockLight() {
            return true;
        }

        @Override
        public boolean isCustomRenderer() {
            return true;
        }

        @Override
        public TextureAtlasSprite getParticleIcon() {
            return original.getParticleIcon();
        }

        @Override
        public ItemOverrides getOverrides() {
            return original.getOverrides();
        }

        @Override
        @SuppressWarnings("deprecation")
        public ItemTransforms getTransforms() {
            return ItemTransforms.NO_TRANSFORMS;
        }
    }

    // ==================== BLADES OF ATHENA ====================
    public static class BladesItem extends Item {
        private static final UUID DMG_UUID = UUID.fromString("cb3f55d3-645c-4f38-a497-9c13a33db5cf");
        private static final UUID SPD_UUID = UUID.fromString("fa233e1c-4180-4865-b01b-bcce9785aca3");
        private final Multimap<Attribute, AttributeModifier> modifiers;

        public BladesItem(Properties props) {
            super(props);
            ImmutableMultimap.Builder<Attribute, AttributeModifier> builder = ImmutableMultimap.builder();
            builder.put(Attributes.ATTACK_DAMAGE, new AttributeModifier(DMG_UUID, "Weapon modifier", 13.0D, AttributeModifier.Operation.ADDITION));
            builder.put(Attributes.ATTACK_SPEED, new AttributeModifier(SPD_UUID, "Weapon modifier", -1.6D, AttributeModifier.Operation.ADDITION));
            this.modifiers = builder.build();
        }

        @Override
        public void initializeClient(Consumer<IClientItemExtensions> consumer) {
            consumer.accept(new IClientItemExtensions() {
                private BlockEntityWithoutLevelRenderer renderer;

                @Override
                public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                    if (renderer == null) {
                        renderer = new OlympusItem3DRenderer();
                    }
                    return renderer;
                }
            });
        }

        @Override
        public Multimap<Attribute, AttributeModifier> getDefaultAttributeModifiers(EquipmentSlot slot) {
            return slot == EquipmentSlot.MAINHAND ? this.modifiers : super.getDefaultAttributeModifiers(slot);
        }

        @Override
        public boolean hurtEnemy(ItemStack stack, LivingEntity target, LivingEntity attacker) {
            target.setSecondsOnFire(6);
            if (!attacker.level().isClientSide && attacker instanceof ServerPlayer player) {
                ServerLevel sLevel = player.serverLevel();
                draw3DChain(sLevel, player.getEyePosition().add(0, -0.3, 0), target.getEyePosition());
                Vec3 pull = player.position().subtract(target.position()).normalize().scale(0.65);
                target.setDeltaMovement(pull.x, 0.38, pull.z);
                target.hurtMarked = true;

                sLevel.sendParticles(ParticleTypes.FLAME, target.getX(), target.getY() + 1.0, target.getZ(), 28, 0.4, 0.5, 0.4, 0.08);
                sLevel.sendParticles(ParticleTypes.LAVA, target.getX(), target.getY() + 1.0, target.getZ(), 10, 0.3, 0.3, 0.3, 0.05);
                sLevel.playSound(null, target.blockPosition(), SoundEvents.CHAIN_BREAK, SoundSource.PLAYERS, 1.2F, 0.8F);
                sLevel.playSound(null, target.blockPosition(), SoundEvents.BLAZE_SHOOT, SoundSource.PLAYERS, 0.8F, 1.3F);
            }
            return super.hurtEnemy(stack, target, attacker);
        }

        @Override
        public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
            ItemStack stack = player.getItemInHand(hand);
            if (!level.isClientSide && player instanceof ServerPlayer sp) {
                if (consumeMagic(sp, 10)) {
                    ServerLevel sLevel = sp.serverLevel();
                    double radius = 7.5D;

                    for (int arm = 0; arm < 12; arm++) {
                        double angle = (arm * 30.0D) * (Math.PI / 180.0D);
                        Vec3 tip = sp.position().add(Math.cos(angle) * radius, 0.9D, Math.sin(angle) * radius);
                        draw3DChain(sLevel, sp.position().add(0, 1.0D, 0), tip);
                        sLevel.sendParticles(ParticleTypes.FLAME, tip.x, tip.y, tip.z, 6, 0.2, 0.2, 0.2, 0.04);
                    }

                    AABB box = sp.getBoundingBox().inflate(radius, 3.0D, radius);
                    for (LivingEntity e : sLevel.getEntitiesOfClass(LivingEntity.class, box, ent -> ent != sp && ent.isAlive())) {
                        e.setSecondsOnFire(8);
                        e.hurt(sp.damageSources().playerAttack(sp), 15.0F);
                        Vec3 away = e.position().subtract(sp.position()).normalize().scale(0.8D);
                        e.setDeltaMovement(away.x, 0.7D, away.z);
                        e.hurtMarked = true;
                    }

                    sLevel.playSound(null, sp.blockPosition(), SoundEvents.CHAIN_PLACE, SoundSource.PLAYERS, 1.5F, 0.6F);
                    sLevel.playSound(null, sp.blockPosition(), SoundEvents.GENERIC_EXPLODE, SoundSource.PLAYERS, 0.9F, 1.2F);
                    sp.swing(InteractionHand.MAIN_HAND, true);
                    sp.swing(InteractionHand.OFF_HAND, true);
                    sp.getCooldowns().addCooldown(this, 16);
                }
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
        }

        @Override
        public void appendHoverText(ItemStack stack, Level level, List<Component> list, TooltipFlag flag) {
            list.add(Component.literal("نصال أثينا المزدوجة - Dual 3D Blades of Athena").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
            list.add(Component.literal("• مجسم 3D متحرك مع سلاسل حقيقية ونصلين مزدوجين").withStyle(ChatFormatting.YELLOW));
            list.add(Component.literal("• كليك يمين: إعصار الفوضى الناري (10 سحر)").withStyle(ChatFormatting.RED));
        }
    }

    // ==================== GOD RELIC ITEMS ====================
    public static class GodRelicItem extends Item {
        private final RelicType type;

        public GodRelicItem(Properties props, RelicType type) {
            super(props);
            this.type = type;
        }

        public RelicType getRelicType() {
            return this.type;
        }

        @Override
        public void initializeClient(Consumer<IClientItemExtensions> consumer) {
            consumer.accept(new IClientItemExtensions() {
                private BlockEntityWithoutLevelRenderer renderer;

                @Override
                public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                    if (renderer == null) {
                        renderer = new OlympusItem3DRenderer();
                    }
                    return renderer;
                }
            });
        }

        @Override
        public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
            ItemStack stack = player.getItemInHand(hand);
            if (!level.isClientSide && player instanceof ServerPlayer sp) {
                switch (this.type) {
                    case CRONOS -> useCronosPower(sp);
                    case BOW -> useBowOfDestiny(sp);
                    case MEDUSA -> useMedusaHead(sp);
                    case ATLAS -> useAtlasQuake(sp);
                }
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
        }

        @Override
        public void appendHoverText(ItemStack stack, Level level, List<Component> list, TooltipFlag flag) {
            switch (this.type) {
                case CRONOS -> {
                    list.add(Component.literal("غضب كرونوس 3D - Cronos's Rage").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
                    list.add(Component.literal("• نواة برق ثلاثية الأبعاد بحلقات دوارة (25 سحر)").withStyle(ChatFormatting.GRAY));
                }
                case BOW -> {
                    list.add(Component.literal("قوس القدر 3D - Bow of Destiny").withStyle(ChatFormatting.BLUE, ChatFormatting.BOLD));
                    list.add(Component.literal("• يطلق أسهم بلازما خارقة بسرعة الضوء (5 سحر)").withStyle(ChatFormatting.GRAY));
                }
                case MEDUSA -> {
                    list.add(Component.literal("رأس ميدوسا الحي 3D - Medusa's Gaze").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));
                    list.add(Component.literal("• 12 ثعبان حي يتحركون + شعاع التجميد الصخري (20 سحر)").withStyle(ChatFormatting.GRAY));
                }
                case ATLAS -> {
                    list.add(Component.literal("زلزال أطلس 3D - Atlas Quake").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
                    list.add(Component.literal("• قفاز الحمم مع صخور مدارية وتفجير الأرض (30 سحر)").withStyle(ChatFormatting.GRAY));
                }
            }
        }
    }

    // ==================== REAL-TIME 60 FPS 3D RENDERER ====================
    @OnlyInClientHelper
    public static class OlympusItem3DRenderer extends BlockEntityWithoutLevelRenderer {
        private static ResourceLocation METAL_TEX;
        private static ResourceLocation GLOW_TEX;

        public OlympusItem3DRenderer() {
            super(Minecraft.getInstance().getBlockEntityRenderDispatcher(), Minecraft.getInstance().getEntityModels());
            ensureProceduralTextures();
        }

        private static void ensureProceduralTextures() {
            if (METAL_TEX != null && GLOW_TEX != null) return;
            Minecraft mc = Minecraft.getInstance();

            NativeImage metalImg = new NativeImage(64, 64, false);
            for (int y = 0; y < 64; y++) {
                for (int x = 0; x < 64; x++) {
                    double grain = Math.sin(x * 0.45 + y * 0.15) * 14.0 + Math.cos(y * 0.6) * 10.0;
                    int border = (x < 2 || x > 61 || y < 2 || y > 61) ? -22 : 0;
                    int shade = Mth.clamp((int) (232 + grain + border), 175, 255);
                    metalImg.setPixelRGBA(x, y, abgr(shade, shade, shade, 255));
                }
            }
            METAL_TEX = mc.getTextureManager().register("olympus_metal_3d", new DynamicTexture(metalImg));

            NativeImage glowImg = new NativeImage(64, 64, false);
            for (int y = 0; y < 64; y++) {
                for (int x = 0; x < 64; x++) {
                    glowImg.setPixelRGBA(x, y, abgr(255, 255, 255, 255));
                }
            }
            GLOW_TEX = mc.getTextureManager().register("olympus_glow_3d", new DynamicTexture(glowImg));
        }

        private static int abgr(int r, int g, int b, int a) {
            return ((a & 0xFF) << 24) | ((b & 0xFF) << 16) | ((g & 0xFF) << 8) | (r & 0xFF);
        }

        @Override
        public void renderByItem(ItemStack stack, ItemDisplayContext ctx, PoseStack ps, MultiBufferSource buffer, int light, int overlay) {
            ensureProceduralTextures();
            float time = (System.currentTimeMillis() % 360000L) / 1000.0F;

            ps.pushPose();
            ps.translate(0.5F, 0.5F, 0.5F);

            Item item = stack.getItem();
            if (item == BLADES.get()) {
                renderBladesOfAthena3D(ps, buffer, ctx, time, light);
            } else if (item instanceof GodRelicItem relic) {
                switch (relic.getRelicType()) {
                    case MEDUSA -> renderMedusaHead3D(ps, buffer, ctx, time, light);
                    case BOW -> renderBowOfDestiny3D(ps, buffer, ctx, time, light);
                    case CRONOS -> renderCronosOrb3D(ps, buffer, ctx, time, light);
                    case ATLAS -> renderAtlasGauntlet3D(ps, buffer, ctx, time, light);
                }
            }
            ps.popPose();
        }

        // -------------------- 1. BLADES OF ATHENA 3D --------------------
        private void renderBladesOfAthena3D(PoseStack ps, MultiBufferSource buffer, ItemDisplayContext ctx, float time, int light) {
            if (ctx == ItemDisplayContext.GUI) {
                ps.scale(0.72F, 0.72F, 0.72F);
                // Twin crossed Blades in GUI icon!
                ps.pushPose();
                ps.translate(-0.06F, -0.05F, -0.05F);
                ps.mulPose(Axis.ZP.rotationDegrees(-42.0F));
                ps.mulPose(Axis.YP.rotationDegrees(15.0F));
                drawSingleAthenaBlade(ps, buffer, time, light, false);
                ps.popPose();

                ps.pushPose();
                ps.translate(0.06F, -0.05F, 0.05F);
                ps.mulPose(Axis.ZP.rotationDegrees(42.0F));
                ps.mulPose(Axis.YP.rotationDegrees(165.0F));
                drawSingleAthenaBlade(ps, buffer, time, light, false);
                ps.popPose();
                return;
            }

            if (ctx == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND || ctx == ItemDisplayContext.FIRST_PERSON_LEFT_HAND) {
                boolean left = (ctx == ItemDisplayContext.FIRST_PERSON_LEFT_HAND);
                ps.translate(left ? 0.12F : -0.12F, -0.12F, 0.05F);
                ps.mulPose(Axis.YP.rotationDegrees(left ? -82.0F : 98.0F));
                ps.mulPose(Axis.ZP.rotationDegrees(-18.0F + (float) Math.sin(time * 3.0F) * 2.5F));
                ps.scale(0.95F, 0.95F, 0.95F);
            } else if (ctx == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND || ctx == ItemDisplayContext.THIRD_PERSON_LEFT_HAND) {
                ps.translate(0.0F, 0.05F, 0.05F);
                ps.mulPose(Axis.YP.rotationDegrees(90.0F));
                ps.mulPose(Axis.ZP.rotationDegrees(-25.0F));
                ps.scale(0.85F, 0.85F, 0.85F);
            } else {
                ps.mulPose(Axis.YP.rotationDegrees(time * 45.0F));
                ps.scale(0.75F, 0.75F, 0.75F);
            }

            drawSingleAthenaBlade(ps, buffer, time, light, true);
        }

        private void drawSingleAthenaBlade(PoseStack ps, MultiBufferSource buffer, float time, int light, boolean animateChain) {
            VertexConsumer metal = buffer.getBuffer(RenderType.entityCutoutNoCull(METAL_TEX));
            int fullBright = LightTexture.FULL_BRIGHT;

            // 1. Pommel Skull + Animated dangling chains
            drawBox(ps, metal, -0.055F, -0.58F, -0.055F, 0.055F, -0.47F, 0.055F, 215, 165, 35, 255, light);
            for (int c = 0; c < 4; c++) {
                ps.pushPose();
                float sway = animateChain ? (float) Math.sin(time * 5.0F + c * 0.9F) * (10.0F + c * 5.0F) : (c * 8.0F);
                ps.translate(0.0F, -0.58F - c * 0.075F, 0.0F);
                ps.mulPose(Axis.ZP.rotationDegrees(sway));
                ps.mulPose(Axis.YP.rotationDegrees(c % 2 == 0 ? 0.0F : 90.0F));
                drawBox(ps, metal, -0.032F, -0.065F, -0.015F, 0.032F, 0.0F, 0.015F, 70, 75, 85, 255, light);
                ps.popPose();
            }

            // 2. Leather & Gold Wrapped Grip
            drawBox(ps, metal, -0.038F, -0.47F, -0.038F, 0.038F, -0.22F, 0.038F, 95, 25, 20, 255, light);
            for (int r = 0; r < 3; r++) {
                float ry = -0.44F + r * 0.085F;
                drawBox(ps, metal, -0.044F, ry, -0.044F, 0.044F, ry + 0.025F, 0.044F, 240, 190, 50, 255, light);
            }

            // 3. Ornate Spartan Winged Guard
            drawBox(ps, metal, -0.16F, -0.22F, -0.05F, 0.16F, -0.14F, 0.05F, 230, 175, 40, 255, light);
            // Left & right curved guard horns
            ps.pushPose();
            ps.translate(0.15F, -0.16F, 0.0F);
            ps.mulPose(Axis.ZP.rotationDegrees(-28.0F));
            drawTapered(ps, metal, 0.07F, 0.07F, 0.015F, 0.02F, 0.16F, 0.02F, 235, 180, 40, 255, 215, 80, 255, light);
            ps.popPose();

            ps.pushPose();
            ps.translate(-0.15F, -0.16F, 0.0F);
            ps.mulPose(Axis.ZP.rotationDegrees(28.0F));
            drawTapered(ps, metal, 0.07F, 0.07F, 0.015F, 0.02F, 0.16F, -0.02F, 235, 180, 40, 255, 215, 80, 255, light);
            ps.popPose();

            // 4. Curved Wide Spartan Blade (6 sculpted segments)
            float[] heights = {0.14F, 0.14F, 0.14F, 0.14F, 0.13F, 0.14F};
            float[] widthsBot = {0.17F, 0.21F, 0.26F, 0.24F, 0.19F, 0.11F};
            float[] widthsTop = {0.21F, 0.26F, 0.24F, 0.19F, 0.11F, 0.008F};
            float[] curveShift = {0.015F, 0.025F, 0.02F, -0.01F, -0.03F, -0.04F};

            float curY = -0.14F;
            float curX = 0.0F;
            for (int i = 0; i < heights.length; i++) {
                ps.pushPose();
                ps.translate(curX, curY, 0.0F);
                // Dark forged spine + bright razor edge
                drawTapered(ps, metal, widthsBot[i], 0.042F, widthsTop[i], 0.018F, heights[i], curveShift[i],
                        65, 55, 60, 145, 130, 125, 255, light);
                // Outer molten bevel
                drawTapered(ps, metal, widthsBot[i] * 1.06F, 0.016F, widthsTop[i] * 1.06F, 0.008F, heights[i], curveShift[i],
                        245, 120, 25, 255, 210, 65, 255, fullBright);
                ps.popPose();

                // Iconic wicked forward hook at segment 2
                if (i == 2) {
                    ps.pushPose();
                    ps.translate(curX + 0.13F, curY + 0.04F, 0.0F);
                    ps.mulPose(Axis.ZP.rotationDegrees(-52.0F));
                    drawTapered(ps, metal, 0.09F, 0.028F, 0.01F, 0.008F, 0.17F, 0.0F, 240, 100, 20, 255, 220, 90, 255, fullBright);
                    ps.popPose();
                }

                curY += heights[i];
                curX += curveShift[i];
            }

            // 5. Glowing Pulsing Greek Runes along the blade center
            VertexConsumer glow = buffer.getBuffer(RenderType.eyes(GLOW_TEX));
            int pulse = (int) (185 + Math.sin(time * 6.0F) * 70);
            drawBox(ps, glow, -0.028F, -0.17F, -0.056F, 0.028F, -0.13F, 0.056F, 255, 50, 20, 255, fullBright);
            for (int r = 0; r < 4; r++) {
                float ry = -0.06F + r * 0.14F;
                drawBox(ps, glow, -0.035F, ry, -0.025F, 0.035F, ry + 0.08F, 0.025F, 255, pulse, 30, 255, fullBright);
            }
        }

        // -------------------- 2. MEDUSA'S HEAD 3D (WITH 12 LIVING SNAKES) --------------------
        private void renderMedusaHead3D(PoseStack ps, MultiBufferSource buffer, ItemDisplayContext ctx, float time, int light) {
            if (ctx == ItemDisplayContext.GUI) {
                ps.scale(0.85F, 0.85F, 0.85F);
                ps.mulPose(Axis.XP.rotationDegrees(14.0F));
                ps.mulPose(Axis.YP.rotationDegrees(-25.0F + (float) Math.sin(time * 1.8F) * 12.0F));
            } else if (ctx == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND || ctx == ItemDisplayContext.FIRST_PERSON_LEFT_HAND) {
                ps.translate(0.0F, -0.05F + (float) Math.sin(time * 3.5F) * 0.025F, 0.0F);
                ps.mulPose(Axis.YP.rotationDegrees(160.0F));
            } else {
                ps.mulPose(Axis.YP.rotationDegrees(time * 35.0F));
            }

            VertexConsumer metal = buffer.getBuffer(RenderType.entityCutoutNoCull(METAL_TEX));
            int fullBright = LightTexture.FULL_BRIGHT;

            // 1. Sculpted Gorgon Head & Cheekbones
            drawTapered(ps, metal, 0.24F, 0.24F, 0.34F, 0.32F, 0.20F, 0.0F, 55, 110, 60, 75, 145, 78, 255, light);
            drawTapered(ps, metal, 0.34F, 0.32F, 0.28F, 0.28F, 0.22F, 0.0F, 75, 145, 78, 60, 120, 65, 255, light);
            // Screaming open jaw + white fangs
            ps.pushPose();
            ps.translate(0.0F, -0.18F, 0.0F);
            drawTapered(ps, metal, 0.18F, 0.20F, 0.24F, 0.24F, 0.18F, 0.0F, 45, 90, 50, 55, 110, 60, 255, light);
            // Dark mouth cavity
            drawBox(ps, metal, -0.08F, 0.05F, 0.08F, 0.08F, 0.16F, 0.135F, 20, 10, 15, 255, light);
            // Sharp fangs
            drawBox(ps, metal, -0.065F, 0.12F, 0.12F, -0.045F, 0.16F, 0.14F, 240, 240, 225, 255, light);
            drawBox(ps, metal, 0.045F, 0.12F, 0.12F, 0.065F, 0.16F, 0.14F, 240, 240, 225, 255, light);
            ps.popPose();

            // Nose bridge & angry brow ridge
            drawBox(ps, metal, -0.03F, 0.04F, 0.14F, 0.03F, 0.20F, 0.19F, 70, 135, 72, 255, light);
            drawBox(ps, metal, -0.15F, 0.19F, 0.13F, 0.15F, 0.24F, 0.185F, 45, 95, 50, 255, light);

            // 2. 12 Animated Slithering 3D Snakes on Head
            for (int s = 0; s < 12; s++) {
                float angleDeg = s * 30.0F;
                float rad = angleDeg * ((float) Math.PI / 180.0F);
                float baseX = Mth.cos(rad) * 0.12F;
                float baseZ = Mth.sin(rad) * 0.11F;
                float baseY = (s % 3 == 0) ? 0.36F : 0.30F;

                ps.pushPose();
                ps.translate(baseX, baseY, baseZ);
                ps.mulPose(Axis.YP.rotationDegrees(-angleDeg + 90.0F));
                ps.mulPose(Axis.XP.rotationDegrees(25.0F + (s % 2) * 15.0F));

                for (int seg = 0; seg < 4; seg++) {
                    float waveYaw = (float) Math.sin(time * 4.8F + s * 0.85F + seg * 0.9F) * 22.0F;
                    float wavePitch = (float) Math.cos(time * 3.6F + s * 0.7F + seg * 0.8F) * 14.0F;
                    ps.mulPose(Axis.ZP.rotationDegrees(waveYaw));
                    ps.mulPose(Axis.XP.rotationDegrees(wavePitch));

                    float w1 = 0.065F - seg * 0.011F;
                    float w2 = 0.054F - seg * 0.011F;
                    drawTapered(ps, metal, w1, w1, w2, w2, 0.095F, 0.0F,
                            35, 95 + seg * 18, 45, 50, 130 + seg * 18, 60, 255, light);
                    ps.translate(0.0F, 0.09F, 0.0F);
                }
                // Snake Head
                drawBox(ps, metal, -0.032F, 0.0F, -0.04F, 0.032F, 0.045F, 0.045F, 65, 165, 75, 255, light);
                ps.popPose();
            }

            // 3. Glowing Emerald Eyes & Petrifying Gaze Aura
            VertexConsumer glow = buffer.getBuffer(RenderType.eyes(GLOW_TEX));
            drawBox(ps, glow, -0.12F, 0.12F, 0.145F, -0.045F, 0.185F, 0.18F, 30, 255, 110, 255, fullBright);
            drawBox(ps, glow, 0.045F, 0.12F, 0.145F, 0.12F, 0.185F, 0.18F, 30, 255, 110, 255, fullBright);
        }

        // -------------------- 3. BOW OF DESTINY 3D --------------------
        private void renderBowOfDestiny3D(PoseStack ps, MultiBufferSource buffer, ItemDisplayContext ctx, float time, int light) {
            if (ctx == ItemDisplayContext.GUI) {
                ps.scale(0.68F, 0.68F, 0.68F);
                ps.mulPose(Axis.ZP.rotationDegrees(-35.0F));
                ps.mulPose(Axis.YP.rotationDegrees(20.0F));
            } else if (ctx == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND || ctx == ItemDisplayContext.FIRST_PERSON_LEFT_HAND) {
                ps.translate(0.0F, 0.0F, 0.05F);
                ps.mulPose(Axis.YP.rotationDegrees(85.0F));
            } else {
                ps.mulPose(Axis.YP.rotationDegrees(time * 40.0F));
                ps.scale(0.8F, 0.8F, 0.8F);
            }

            VertexConsumer metal = buffer.getBuffer(RenderType.entityCutoutNoCull(METAL_TEX));
            int fullBright = LightTexture.FULL_BRIGHT;

            // Golden Winged Grip
            drawBox(ps, metal, -0.055F, -0.14F, -0.055F, 0.055F, 0.14F, 0.055F, 240, 190, 50, 255, light);

            // Upper & Lower Sculpted Recurve Limbs
            for (int dir = -1; dir <= 1; dir += 2) {
                ps.pushPose();
                if (dir == -1) {
                    ps.mulPose(Axis.ZP.rotationDegrees(180.0F));
                }
                ps.translate(0.0F, 0.14F, 0.0F);
                float[] angles = {-16.0F, -22.0F, -24.0F, 18.0F};
                for (int i = 0; i < 4; i++) {
                    ps.mulPose(Axis.ZP.rotationDegrees(angles[i]));
                    float w1 = 0.09F - i * 0.015F;
                    float w2 = 0.075F - i * 0.015F;
                    drawTapered(ps, metal, w1, 0.065F, w2, 0.05F, 0.16F, 0.0F,
                            225, 175, 40, 250, 215, 75, 255, light);
                    ps.translate(0.0F, 0.155F, 0.0F);
                }
                ps.popPose();
            }

            // Glowing Energy Bowstring & Spinning Plasma Arrow
            VertexConsumer glow = buffer.getBuffer(RenderType.eyes(GLOW_TEX));
            drawBox(ps, glow, -0.28F, -0.58F, -0.01F, -0.26F, 0.58F, 0.01F, 60, 230, 255, 255, fullBright);

            // Spinning Plasma Arrow
            ps.pushPose();
            float pull = (float) Math.sin(time * 5.0F) * 0.04F;
            ps.translate(-0.27F + pull, 0.0F, 0.0F);
            ps.mulPose(Axis.XP.rotationDegrees(time * 260.0F));
            drawBox(ps, glow, -0.02F, -0.015F, -0.015F, 0.58F, 0.015F, 0.015F, 120, 245, 255, 255, fullBright);
            // Arrowhead
            ps.translate(0.58F, 0.0F, 0.0F);
            ps.mulPose(Axis.ZP.rotationDegrees(-90.0F));
            drawTapered(ps, glow, 0.09F, 0.09F, 0.005F, 0.005F, 0.16F, 0.0F, 50, 210, 255, 255, 255, 255, 255, fullBright);
            ps.popPose();
        }

        // -------------------- 4. CRONOS'S RAGE 3D (GYROSCOPE LIGHTNING CORE) --------------------
        private void renderCronosOrb3D(PoseStack ps, MultiBufferSource buffer, ItemDisplayContext ctx, float time, int light) {
            if (ctx == ItemDisplayContext.GUI) {
                ps.scale(0.85F, 0.85F, 0.85F);
            }
            int fullBright = LightTexture.FULL_BRIGHT;
            VertexConsumer metal = buffer.getBuffer(RenderType.entityCutoutNoCull(METAL_TEX));

            // 3 Gyroscopic Rotating Golden Titan Rings
            for (int ring = 0; ring < 3; ring++) {
                ps.pushPose();
                if (ring == 0) {
                    ps.mulPose(Axis.YP.rotationDegrees(time * 95.0F));
                    ps.mulPose(Axis.XP.rotationDegrees(35.0F));
                } else if (ring == 1) {
                    ps.mulPose(Axis.XP.rotationDegrees(time * -110.0F));
                    ps.mulPose(Axis.ZP.rotationDegrees(45.0F));
                } else {
                    ps.mulPose(Axis.ZP.rotationDegrees(time * 80.0F));
                    ps.mulPose(Axis.YP.rotationDegrees(60.0F));
                }

                float radius = 0.27F + ring * 0.045F;
                for (int seg = 0; seg < 12; seg++) {
                    ps.pushPose();
                    ps.mulPose(Axis.ZP.rotationDegrees(seg * 30.0F));
                    drawBox(ps, metal, -0.045F, radius, -0.025F, 0.045F, radius + 0.032F, 0.025F,
                            235, 190, 55, 255, light);
                    ps.popPose();
                }
                ps.popPose();
            }

            // Pulsing Octahedral Lightning Core + Jagged Bolts
            VertexConsumer glow = buffer.getBuffer(RenderType.eyes(GLOW_TEX));
            ps.pushPose();
            ps.mulPose(Axis.YP.rotationDegrees(time * 140.0F));
            ps.mulPose(Axis.XP.rotationDegrees(time * 90.0F));
            drawTapered(ps, glow, 0.22F, 0.22F, 0.01F, 0.01F, 0.22F, 0.0F, 40, 200, 255, 255, 255, 255, 255, fullBright);
            ps.mulPose(Axis.XP.rotationDegrees(180.0F));
            drawTapered(ps, glow, 0.22F, 0.22F, 0.01F, 0.01F, 0.22F, 0.0F, 40, 200, 255, 255, 255, 255, 255, fullBright);
            ps.popPose();
        }

        // -------------------- 5. ATLAS QUAKE 3D (GAUNTLET + ORBITING BOULDERS) --------------------
        private void renderAtlasGauntlet3D(PoseStack ps, MultiBufferSource buffer, ItemDisplayContext ctx, float time, int light) {
            if (ctx == ItemDisplayContext.GUI) {
                ps.scale(0.78F, 0.78F, 0.78F);
                ps.mulPose(Axis.XP.rotationDegrees(22.0F));
                ps.mulPose(Axis.YP.rotationDegrees(38.0F));
            } else if (ctx == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND || ctx == ItemDisplayContext.FIRST_PERSON_LEFT_HAND) {
                ps.translate(0.0F, -0.08F, 0.0F);
                ps.mulPose(Axis.YP.rotationDegrees(45.0F));
            } else {
                ps.mulPose(Axis.YP.rotationDegrees(time * 35.0F));
            }

            int fullBright = LightTexture.FULL_BRIGHT;
            VertexConsumer metal = buffer.getBuffer(RenderType.entityCutoutNoCull(METAL_TEX));

            // Heavy Cracked Obsidian Titan Fist
            drawBox(ps, metal, -0.20F, -0.22F, -0.20F, 0.20F, 0.22F, 0.20F, 48, 42, 46, 255, light);
            // Knuckle Spikes
            for (int k = -1; k <= 1; k++) {
                ps.pushPose();
                ps.translate(k * 0.11F, 0.22F, 0.0F);
                drawTapered(ps, metal, 0.08F, 0.08F, 0.015F, 0.015F, 0.14F, 0.0F, 70, 60, 65, 200, 90, 25, 255, light);
                ps.popPose();
            }

            // Orbiting 3D Magma Boulders
            for (int b = 0; b < 4; b++) {
                ps.pushPose();
                float orbitAngle = time * 110.0F + b * 90.0F;
                ps.mulPose(Axis.YP.rotationDegrees(orbitAngle));
                ps.translate(0.36F, (float) Math.sin(time * 4.0F + b) * 0.08F, 0.0F);
                ps.mulPose(Axis.XP.rotationDegrees(time * 160.0F));
                drawBox(ps, metal, -0.065F, -0.065F, -0.065F, 0.065F, 0.065F, 0.065F, 90, 45, 30, 255, light);
                ps.popPose();
            }

            // Glowing Molten Magma Core Seams
            VertexConsumer glow = buffer.getBuffer(RenderType.eyes(GLOW_TEX));
            drawBox(ps, glow, -0.215F, -0.06F, -0.215F, 0.215F, 0.06F, 0.215F, 255, 115, 20, 255, fullBright);
            drawBox(ps, glow, -0.06F, -0.23F, -0.215F, 0.06F, 0.23F, 0.215F, 255, 165, 35, 255, fullBright);
        }

        // -------------------- 3D GEOMETRY PRIMITIVES --------------------
        private void drawBox(PoseStack ps, VertexConsumer vc, float x1, float y1, float z1, float x2, float y2, float z2,
                             int r, int g, int b, int a, int light) {
            Matrix4f pose = ps.last().pose();
            Matrix3f norm = ps.last().normal();

            // Front (Z+)
            quad(vc, pose, norm, x1, y1, z2, x2, y1, z2, x2, y2, z2, x1, y2, z2, 0, 0, 1, r, g, b, a, light);
            // Back (Z-)
            quad(vc, pose, norm, x2, y1, z1, x1, y1, z1, x1, y2, z1, x2, y2, z1, 0, 0, -1, r, g, b, a, light);
            // Top (Y+)
            quad(vc, pose, norm, x1, y2, z2, x2, y2, z2, x2, y2, z1, x1, y2, z1, 0, 1, 0, r, g, b, a, light);
            // Bottom (Y-)
            quad(vc, pose, norm, x1, y1, z1, x2, y1, z1, x2, y1, z2, x1, y1, z2, 0, -1, 0, r, g, b, a, light);
            // Right (X+)
            quad(vc, pose, norm, x2, y1, z2, x2, y1, z1, x2, y2, z1, x2, y2, z2, 1, 0, 0, r, g, b, a, light);
            // Left (X-)
            quad(vc, pose, norm, x1, y1, z1, x1, y1, z2, x1, y2, z2, x1, y2, z1, -1, 0, 0, r, g, b, a, light);
        }

        private void drawTapered(PoseStack ps, VertexConsumer vc, float wBot, float dBot, float wTop, float dTop, float h, float shiftXTop,
                                 int r1, int g1, int b1, int r2, int g2, int b2, int a, int light) {
            Matrix4f pose = ps.last().pose();
            Matrix3f norm = ps.last().normal();

            float x1b = -wBot * 0.5F, x2b = wBot * 0.5F, z1b = -dBot * 0.5F, z2b = dBot * 0.5F;
            float x1t = shiftXTop - wTop * 0.5F, x2t = shiftXTop + wTop * 0.5F, z1t = -dTop * 0.5F, z2t = dTop * 0.5F;

            // Front
            gradQuad(vc, pose, norm, x1b, 0, z2b, x2b, 0, z2b, x2t, h, z2t, x1t, h, z2t, 0, 0, 1, r1, g1, b1, r2, g2, b2, a, light);
            // Back
            gradQuad(vc, pose, norm, x2b, 0, z1b, x1b, 0, z1b, x1t, h, z1t, x2t, h, z1t, 0, 0, -1, r1, g1, b1, r2, g2, b2, a, light);
            // Right
            gradQuad(vc, pose, norm, x2b, 0, z2b, x2b, 0, z1b, x2t, h, z1t, x2t, h, z2t, 1, 0, 0, r1, g1, b1, r2, g2, b2, a, light);
            // Left
            gradQuad(vc, pose, norm, x1b, 0, z1b, x1b, 0, z2b, x1t, h, z2t, x1t, h, z1t, -1, 0, 0, r1, g1, b1, r2, g2, b2, a, light);
            // Top
            quad(vc, pose, norm, x1t, h, z2t, x2t, h, z2t, x2t, h, z1t, x1t, h, z1t, 0, 1, 0, r2, g2, b2, a, light);
            // Bottom
            quad(vc, pose, norm, x1b, 0, z1b, x2b, 0, z1b, x2b, 0, z2b, x1b, 0, z2b, 0, -1, 0, r1, g1, b1, a, light);
        }

        private void quad(VertexConsumer vc, Matrix4f pose, Matrix3f norm,
                          float x1, float y1, float z1, float x2, float y2, float z2,
                          float x3, float y3, float z3, float x4, float y4, float z4,
                          float nx, float ny, float nz, int r, int g, int b, int a, int light) {
            vert(vc, pose, norm, x1, y1, z1, 0, 1, nx, ny, nz, r, g, b, a, light);
            vert(vc, pose, norm, x2, y2, z2, 1, 1, nx, ny, nz, r, g, b, a, light);
            vert(vc, pose, norm, x3, y3, z3, 1, 0, nx, ny, nz, r, g, b, a, light);
            vert(vc, pose, norm, x4, y4, z4, 0, 0, nx, ny, nz, r, g, b, a, light);
        }

        private void gradQuad(VertexConsumer vc, Matrix4f pose, Matrix3f norm,
                              float x1, float y1, float z1, float x2, float y2, float z2,
                              float x3, float y3, float z3, float x4, float y4, float z4,
                              float nx, float ny, float nz,
                              int r1, int g1, int b1, int r2, int g2, int b2, int a, int light) {
            vert(vc, pose, norm, x1, y1, z1, 0, 1, nx, ny, nz, r1, g1, b1, a, light);
            vert(vc, pose, norm, x2, y2, z2, 1, 1, nx, ny, nz, r1, g1, b1, a, light);
            vert(vc, pose, norm, x3, y3, z3, 1, 0, nx, ny, nz, r2, g2, b2, a, light);
            vert(vc, pose, norm, x4, y4, z4, 0, 0, nx, ny, nz, r2, g2, b2, a, light);
        }

        private void vert(VertexConsumer vc, Matrix4f pose, Matrix3f norm,
                          float x, float y, float z, float u, float v,
                          float nx, float ny, float nz, int r, int g, int b, int a, int light) {
            vc.vertex(pose, x, y, z)
                    .color(r, g, b, a)
                    .uv(u, v)
                    .overlayCoords(OverlayTexture.NO_OVERLAY)
                    .uv2(light)
                    .normal(norm, nx, ny, nz)
                    .endVertex();
        }
    }

    public @interface OnlyInClientHelper {}

    // ==================== ABILITY IMPLEMENTATIONS ====================
    private static void draw3DChain(ServerLevel level, Vec3 from, Vec3 to) {
        DustParticleOptions darkIron = new DustParticleOptions(new Vector3f(0.35F, 0.35F, 0.38F), 1.25F);
        DustParticleOptions fireGlow = new DustParticleOptions(new Vector3f(1.0F, 0.45F, 0.05F), 1.1F);
        int links = 20;
        for (int i = 0; i <= links; i++) {
            double t = i / (double) links;
            double x = Mth.lerp(t, from.x, to.x);
            double y = Mth.lerp(t, from.y, to.y) - Math.sin(t * Math.PI) * 0.25D;
            double z = Mth.lerp(t, from.z, to.z);
            level.sendParticles(darkIron, x, y, z, 1, 0.02, 0.02, 0.02, 0.0);
            if (i % 2 == 0) {
                level.sendParticles(fireGlow, x, y, z, 1, 0.04, 0.04, 0.04, 0.0);
            }
        }
    }

    private static void useCronosPower(ServerPlayer sp) {
        if (!consumeMagic(sp, 25)) return;
        ServerLevel sLevel = sp.serverLevel();
        AABB area = sp.getBoundingBox().inflate(14.0D, 6.0D, 14.0D);
        List<LivingEntity> targets = sLevel.getEntitiesOfClass(LivingEntity.class, area, e -> e != sp && e.isAlive());
        targets.sort(Comparator.comparingDouble(sp::distanceToSqr));

        DustParticleOptions cyanElectric = new DustParticleOptions(new Vector3f(0.1F, 0.85F, 1.0F), 1.4F);
        Vec3 prevPos = sp.getEyePosition();
        int count = 0;
        for (LivingEntity target : targets) {
            if (count >= 8) break;
            Vec3 curPos = target.getEyePosition();
            for (int i = 0; i <= 14; i++) {
                double t = i / 14.0D;
                Vec3 p = prevPos.lerp(curPos, t);
                sLevel.sendParticles(cyanElectric, p.x, p.y, p.z, 2, 0.08, 0.08, 0.08, 0.0);
                sLevel.sendParticles(ParticleTypes.ELECTRIC_SPARK, p.x, p.y, p.z, 2, 0.1, 0.1, 0.1, 0.02);
            }
            prevPos = curPos;

            LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(sLevel);
            if (bolt != null) {
                bolt.moveTo(target.position());
                bolt.setVisualOnly(true);
                sLevel.addFreshEntity(bolt);
            }
            target.hurt(sp.damageSources().lightningBolt(), 16.0F);
            target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 70, 3));
            count++;
        }

        sLevel.playSound(null, sp.blockPosition(), SoundEvents.TRIDENT_THUNDER, SoundSource.PLAYERS, 1.8F, 1.0F);
        sp.swing(InteractionHand.MAIN_HAND, true);
        sp.getCooldowns().addCooldown(CRONOS.get(), 45);
    }

    private static void useBowOfDestiny(ServerPlayer sp) {
        if (!consumeMagic(sp, 5)) return;
        ServerLevel sLevel = sp.serverLevel();
        Vec3 start = sp.getEyePosition();
        Vec3 look = sp.getLookAngle();
        DustParticleOptions cyanBeam = new DustParticleOptions(new Vector3f(0.0F, 0.9F, 1.0F), 1.3F);

        for (double d = 1.0D; d <= 32.0D; d += 0.7D) {
            Vec3 p = start.add(look.scale(d));
            sLevel.sendParticles(cyanBeam, p.x, p.y, p.z, 2, 0.03, 0.03, 0.03, 0.0);
            sLevel.sendParticles(ParticleTypes.END_ROD, p.x, p.y, p.z, 1, 0.02, 0.02, 0.02, 0.0);

            AABB hitBox = new AABB(p.x - 0.85, p.y - 0.85, p.z - 0.85, p.x + 0.85, p.y + 0.85, p.z + 0.85);
            List<LivingEntity> hit = sLevel.getEntitiesOfClass(LivingEntity.class, hitBox, e -> e != sp && e.isAlive());
            if (!hit.isEmpty()) {
                for (LivingEntity target : hit) {
                    target.hurt(sp.damageSources().playerAttack(sp), 12.0F);
                    sLevel.sendParticles(ParticleTypes.SONIC_BOOM, target.getX(), target.getY() + 1.0, target.getZ(), 1, 0, 0, 0, 0);
                }
                break;
            }
        }

        sLevel.playSound(null, sp.blockPosition(), SoundEvents.ARROW_SHOOT, SoundSource.PLAYERS, 1.2F, 1.5F);
        sLevel.playSound(null, sp.blockPosition(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 1.0F, 1.6F);
        sp.swing(InteractionHand.MAIN_HAND, true);
        sp.getCooldowns().addCooldown(BOW.get(), 6);
    }

    private static void useMedusaHead(ServerPlayer sp) {
        if (!consumeMagic(sp, 20)) return;
        ServerLevel sLevel = sp.serverLevel();
        Vec3 eye = sp.getEyePosition();
        Vec3 look = sp.getLookAngle().normalize();
        DustParticleOptions emeraldGaze = new DustParticleOptions(new Vector3f(0.1F, 1.0F, 0.3F), 1.5F);

        for (double d = 1.5D; d <= 11.0D; d += 0.8D) {
            Vec3 center = eye.add(look.scale(d));
            double radius = d * 0.25D;
            for (int a = 0; a < 360; a += 30) {
                double rad = Math.toRadians(a);
                sLevel.sendParticles(emeraldGaze, center.x + Math.cos(rad) * radius, center.y + Math.sin(rad) * radius * 0.5, center.z + Math.sin(rad) * radius, 1, 0.02, 0.02, 0.02, 0.0);
            }
        }

        AABB coneBox = sp.getBoundingBox().inflate(11.0D, 4.0D, 11.0D);
        for (LivingEntity e : sLevel.getEntitiesOfClass(LivingEntity.class, coneBox, ent -> ent != sp && ent.isAlive())) {
            Vec3 dirTo = e.getEyePosition().subtract(eye).normalize();
            if (look.dot(dirTo) > 0.45D) {
                e.getPersistentData().putInt(TAG_PETRIFIED, 160);
                e.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 160, 10, false, false));
                if (e instanceof Mob mob) {
                    mob.setNoAi(true);
                }
                sLevel.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.STONE.defaultBlockState()),
                        e.getX(), e.getY() + 1.0, e.getZ(), 45, 0.4, 0.8, 0.4, 0.1);
                sLevel.playSound(null, e.blockPosition(), SoundEvents.ELDER_GUARDIAN_CURSE, SoundSource.PLAYERS, 1.2F, 1.5F);
            }
        }

        sp.swing(InteractionHand.MAIN_HAND, true);
        sp.getCooldowns().addCooldown(MEDUSA.get(), 40);
    }

    private static void useAtlasQuake(ServerPlayer sp) {
        if (!consumeMagic(sp, 30)) return;
        ServerLevel sLevel = sp.serverLevel();
        BlockPos center = sp.blockPosition();

        for (int i = 0; i < 16; i++) {
            double angle = (i * 22.5D) * (Math.PI / 180.0D);
            double x = sp.getX() + Math.cos(angle) * 2.5D;
            double z = sp.getZ() + Math.sin(angle) * 2.5D;
            BlockState debrisState = (i % 2 == 0) ? Blocks.MAGMA_BLOCK.defaultBlockState() : Blocks.DEEPSLATE.defaultBlockState();
            FallingBlockEntity boulder = FallingBlockEntity.fall(sLevel, BlockPos.containing(x, sp.getY() + 0.6D, z), debrisState);
            boulder.dropItem = false;
            boulder.disableDrop();
            boulder.setDeltaMovement(Math.cos(angle) * 0.42D, 0.65D, Math.sin(angle) * 0.42D);
            boulder.hurtMarked = true;
        }

        sLevel.sendParticles(ParticleTypes.EXPLOSION_EMITTER, sp.getX(), sp.getY(), sp.getZ(), 2, 1.0, 0.2, 1.0, 0.0);
        sLevel.sendParticles(ParticleTypes.LAVA, sp.getX(), sp.getY() + 0.5, sp.getZ(), 45, 3.5, 0.5, 3.5, 0.15);

        AABB quakeBox = sp.getBoundingBox().inflate(10.0D, 4.0D, 10.0D);
        for (LivingEntity e : sLevel.getEntitiesOfClass(LivingEntity.class, quakeBox, ent -> ent != sp && ent.isAlive())) {
            e.hurt(sp.damageSources().playerAttack(sp), 18.0F);
            e.setSecondsOnFire(5);
            Vec3 launch = e.position().subtract(sp.position()).normalize().scale(1.1D);
            e.setDeltaMovement(launch.x, 0.95D, launch.z);
            e.hurtMarked = true;
        }

        sLevel.playSound(null, center, SoundEvents.GENERIC_EXPLODE, SoundSource.PLAYERS, 2.0F, 0.55F);
        sLevel.playSound(null, center, SoundEvents.ANVIL_LAND, SoundSource.PLAYERS, 1.4F, 0.5F);
        sp.swing(InteractionHand.MAIN_HAND, true);
        sp.getCooldowns().addCooldown(ATLAS.get(), 55);
    }

    // ==================== SERVER EVENTS ====================
    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            CompoundTag data = getPersistedTag(sp);
            if (!data.getBoolean(TAG_INIT)) {
                data.putBoolean(TAG_INIT, true);
                data.putInt(TAG_MAGIC, MAX_MAGIC);
                data.putInt(TAG_ORBS, 0);
            }
            if (!data.getBoolean(TAG_STARTER)) {
                data.putBoolean(TAG_STARTER, true);
                giveStarter(sp, new ItemStack(BLADES.get()));
                giveStarter(sp, new ItemStack(CRONOS.get()));
                giveStarter(sp, new ItemStack(BOW.get()));
                giveStarter(sp, new ItemStack(MEDUSA.get()));
                giveStarter(sp, new ItemStack(ATLAS.get()));
            }
            syncHud(sp);
        }
    }

    @SubscribeEvent
    public void onPlayerClone(PlayerEvent.Clone event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            syncHud(sp);
        }
    }

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Player player = event.player;
        if (player.level().isClientSide) return;

        // Automatic Dual-Wielding for Blades of Athena
        ItemStack main = player.getMainHandItem();
        ItemStack off = player.getOffhandItem();
        if (main.is(BLADES.get())) {
            if (off.isEmpty()) {
                ItemStack twin = new ItemStack(BLADES.get());
                twin.getOrCreateTag().putBoolean(TAG_OFFHAND_CLONE, true);
                player.setItemInHand(InteractionHand.OFF_HAND, twin);
            }
        } else if (off.is(BLADES.get()) && off.hasTag() && off.getTag().getBoolean(TAG_OFFHAND_CLONE)) {
            player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
        }

        if (player.tickCount % 20 == 0 && player instanceof ServerPlayer sp) {
            int magic = getMagic(sp);
            if (magic < MAX_MAGIC) {
                setMagic(sp, Math.min(MAX_MAGIC, magic + 3));
            } else {
                syncHud(sp);
            }
        }
    }

    @SubscribeEvent
    public void onLivingTick(LivingEvent.LivingTickEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity.level().isClientSide) return;

        int petrified = entity.getPersistentData().getInt(TAG_PETRIFIED);
        if (petrified > 0) {
            petrified--;
            entity.getPersistentData().putInt(TAG_PETRIFIED, petrified);
            entity.setDeltaMovement(0, Math.min(0, entity.getDeltaMovement().y), 0);

            if (entity.tickCount % 4 == 0 && entity.level() instanceof ServerLevel sLevel) {
                sLevel.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.STONE.defaultBlockState()),
                        entity.getX(), entity.getY() + entity.getBbHeight() * 0.5, entity.getZ(), 4, 0.25, 0.4, 0.25, 0.01);
            }

            if (petrified == 0 && entity instanceof Mob mob) {
                mob.setNoAi(false);
            }
        }
    }

    @SubscribeEvent
    public void onLivingHurt(LivingHurtEvent event) {
        LivingEntity target = event.getEntity();
        if (!target.level().isClientSide && target.getPersistentData().getInt(TAG_PETRIFIED) > 0) {
            target.getPersistentData().putInt(TAG_PETRIFIED, 0);
            if (target instanceof Mob mob) {
                mob.setNoAi(false);
            }
            if (target.level() instanceof ServerLevel sLevel) {
                sLevel.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.COBBLESTONE.defaultBlockState()),
                        target.getX(), target.getY() + 1.0, target.getZ(), 90, 0.5, 0.8, 0.5, 0.2);
                sLevel.playSound(null, target.blockPosition(), SoundEvents.STONE_BREAK, SoundSource.BLOCKS, 2.0F, 0.7F);
                sLevel.playSound(null, target.blockPosition(), SoundEvents.GLASS_BREAK, SoundSource.BLOCKS, 1.5F, 0.6F);
            }
            event.setAmount(target.getMaxHealth() * 10.0F);
        }
    }

    @SubscribeEvent
    public void onLivingDeath(LivingDeathEvent event) {
        if (event.getSource().getEntity() instanceof ServerPlayer sp) {
            CompoundTag data = getPersistedTag(sp);
            int orbs = data.getInt(TAG_ORBS) + 15;
            data.putInt(TAG_ORBS, orbs);
            if (sp.level() instanceof ServerLevel sLevel) {
                LivingEntity dead = event.getEntity();
                DustParticleOptions redOrb = new DustParticleOptions(new Vector3f(1.0F, 0.08F, 0.08F), 1.5F);
                sLevel.sendParticles(redOrb, dead.getX(), dead.getY() + 1.0, dead.getZ(), 30, 0.4, 0.5, 0.4, 0.08);
            }
            syncHud(sp);
        }
    }

    // ==================== DATA & NETWORK ====================
    private static void giveStarter(ServerPlayer sp, ItemStack stack) {
        if (!sp.getInventory().contains(stack)) {
            sp.getInventory().add(stack);
        }
    }

    private static CompoundTag getPersistedTag(ServerPlayer sp) {
        CompoundTag root = sp.getPersistentData();
        if (!root.contains(Player.PERSISTED_NBT_TAG)) {
            root.put(Player.PERSISTED_NBT_TAG, new CompoundTag());
        }
        return root.getCompound(Player.PERSISTED_NBT_TAG);
    }

    private static int getMagic(ServerPlayer sp) {
        CompoundTag tag = getPersistedTag(sp);
        return tag.contains(TAG_MAGIC) ? tag.getInt(TAG_MAGIC) : MAX_MAGIC;
    }

    private static void setMagic(ServerPlayer sp, int value) {
        getPersistedTag(sp).putInt(TAG_MAGIC, Mth.clamp(value, 0, MAX_MAGIC));
        syncHud(sp);
    }

    private static boolean consumeMagic(ServerPlayer sp, int cost) {
        if (sp.isCreative()) return true;
        int current = getMagic(sp);
        if (current < cost) {
            sp.displayClientMessage(Component.literal("⚡ السحر غير كافٍ! (" + current + "/" + cost + ")").withStyle(ChatFormatting.RED, ChatFormatting.BOLD), true);
            sp.level().playSound(null, sp.blockPosition(), SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 0.7F, 1.5F);
            return false;
        }
        setMagic(sp, current - cost);
        return true;
    }

    private static void syncHud(ServerPlayer sp) {
        CompoundTag tag = getPersistedTag(sp);
        NETWORK.send(PacketDistributor.PLAYER.with(() -> sp), new HudSyncPacket(getMagic(sp), tag.getInt(TAG_ORBS)));
    }

    public static class HudSyncPacket {
        private final int magic;
        private final int redOrbs;

        public HudSyncPacket(int magic, int redOrbs) {
            this.magic = magic;
            this.redOrbs = redOrbs;
        }

        public static void encode(HudSyncPacket msg, FriendlyByteBuf buf) {
            buf.writeInt(msg.magic);
            buf.writeInt(msg.redOrbs);
        }

        public static HudSyncPacket decode(FriendlyByteBuf buf) {
            return new HudSyncPacket(buf.readInt(), buf.readInt());
        }

        public static void handle(HudSyncPacket msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ClientState.magic = msg.magic;
                ClientState.redOrbs = msg.redOrbs;
            });
            ctx.get().setPacketHandled(true);
        }
    }

    public static class ClientState {
        public static int magic = 100;
        public static int redOrbs = 0;
    }

    // ==================== GOD OF WAR 2 TOP-LEFT HUD ====================
    @Mod.EventBusSubscriber(modid = MODID, value = Dist.CLIENT)
    public static class ClientHudOverlay {
        @SubscribeEvent
        public static void onRenderGui(RenderGuiOverlayEvent.Post event) {
            if (!event.getOverlay().id().getPath().equals("hotbar")) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.options.hideGui) return;

            GuiGraphics g = event.getGuiGraphics();
            int x = 12;
            int y = 12;

            // Golden Greek Omega Emblem Badge
            g.fill(x, y, x + 36, y + 36, 0xFF996B15);
            g.fill(x + 2, y + 2, x + 34, y + 34, 0xFFE6B83A);
            g.fill(x + 4, y + 4, x + 32, y + 32, 0xFF1A0D0A);
            g.drawCenteredString(mc.font, "Ω", x + 18, y + 10, 0xFFFFD700);
            g.drawCenteredString(mc.font, "GOW II", x + 18, y + 21, 0xFFFF4500);

            int barX = x + 40;
            int barWidth = 148;
            float hpPct = Mth.clamp(mc.player.getHealth() / Math.max(1.0F, mc.player.getMaxHealth()), 0.0F, 1.0F);
            int hpFill = (int) (barWidth * hpPct);

            // Health Bar (Spartan Crimson)
            g.fill(barX - 2, y + 2, barX + barWidth + 2, y + 16, 0xFFD4AF37);
            g.fill(barX, y + 4, barX + barWidth, y + 14, 0xFF220505);
            if (hpFill > 0) {
                g.fill(barX, y + 4, barX + hpFill, y + 9, 0xFFFF3824);
                g.fill(barX, y + 9, barX + hpFill, y + 14, 0xFF9E0B0B);
            }
            g.drawString(mc.font, "HP " + (int) Math.ceil(mc.player.getHealth()) + "/" + (int) mc.player.getMaxHealth(), barX + 5, y + 5, 0xFFFFFFFF, true);

            // Magic Bar (Olympian Cyan)
            float mpPct = Mth.clamp(ClientState.magic / (float) MAX_MAGIC, 0.0F, 1.0F);
            int mpFill = (int) (barWidth * mpPct);
            int mpY = y + 20;
            g.fill(barX - 2, mpY, barX + barWidth + 2, mpY + 14, 0xFFD4AF37);
            g.fill(barX, mpY + 2, barX + barWidth, mpY + 12, 0xFF051329);
            if (mpFill > 0) {
                g.fill(barX, mpY + 2, barX + mpFill, mpY + 7, 0xFF2CE8FF);
                g.fill(barX, mpY + 7, barX + mpFill, mpY + 12, 0xFF0066CC);
            }
            g.drawString(mc.font, "MP " + ClientState.magic + "/" + MAX_MAGIC, barX + 5, mpY + 3, 0xFFFFFFFF, true);

            // Red Orbs Counter
            g.fill(barX - 2, mpY + 17, barX + 98, mpY + 30, 0xCC2A0404);
            g.drawString(mc.font, "● RED ORBS: " + ClientState.redOrbs, barX + 3, mpY + 19, 0xFFFF3B3B, true);
        }
    }
}
