package dev.appliedcausality.content;

import appeng.blockentity.AEBaseBlockEntity;
import dev.appliedcausality.AppliedCausality;
import dev.appliedcausality.block.SingularPatternProviderBlock;
import dev.appliedcausality.blockentity.SingularPatternProviderBlockEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** Registers the Phase 6 Singular Pattern Provider content. */
public final class AppliedCausalityContent {
    private static final String SINGULAR_PATTERN_PROVIDER_ID = "singular_pattern_provider";
    private static final String CREATIVE_SINGULAR_PATTERN_PROVIDER_ID = "creative_singular_pattern_provider";

    private static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, AppliedCausality.MOD_ID);
    private static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, AppliedCausality.MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, AppliedCausality.MOD_ID);
    private static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(ForgeRegistries.MENU_TYPES, AppliedCausality.MOD_ID);

    public static final RegistryObject<SingularPatternProviderBlock> SINGULAR_PATTERN_PROVIDER =
            BLOCKS.register(SINGULAR_PATTERN_PROVIDER_ID, SingularPatternProviderBlock::new);
    public static final RegistryObject<Item> SINGULAR_PATTERN_PROVIDER_ITEM =
            ITEMS.register(SINGULAR_PATTERN_PROVIDER_ID,
                    () -> new BlockItem(SINGULAR_PATTERN_PROVIDER.get(), new Item.Properties()));
    public static final RegistryObject<SingularPatternProviderBlock> CREATIVE_SINGULAR_PATTERN_PROVIDER =
            BLOCKS.register(CREATIVE_SINGULAR_PATTERN_PROVIDER_ID, SingularPatternProviderBlock::new);
    public static final RegistryObject<Item> CREATIVE_SINGULAR_PATTERN_PROVIDER_ITEM =
            ITEMS.register(CREATIVE_SINGULAR_PATTERN_PROVIDER_ID,
                    () -> new BlockItem(CREATIVE_SINGULAR_PATTERN_PROVIDER.get(), new Item.Properties()));
    public static final RegistryObject<BlockEntityType<SingularPatternProviderBlockEntity>>
            SINGULAR_PATTERN_PROVIDER_BLOCK_ENTITY = BLOCK_ENTITY_TYPES.register(
                    SINGULAR_PATTERN_PROVIDER_ID,
                    () -> BlockEntityType.Builder.of(
                            SingularPatternProviderBlockEntity::new,
                            SINGULAR_PATTERN_PROVIDER.get()).build(null));
    public static final RegistryObject<BlockEntityType<SingularPatternProviderBlockEntity>>
            CREATIVE_SINGULAR_PATTERN_PROVIDER_BLOCK_ENTITY = BLOCK_ENTITY_TYPES.register(
                    CREATIVE_SINGULAR_PATTERN_PROVIDER_ID,
                    () -> BlockEntityType.Builder.of(
                            SingularPatternProviderBlockEntity::new,
                            CREATIVE_SINGULAR_PATTERN_PROVIDER.get()).build(null));
    public static final RegistryObject<MenuType<dev.appliedcausality.menu.ProviderBindingMenu>>
            PROVIDER_BINDING_MENU = MENUS.register("provider_binding",
                    () -> IForgeMenuType.create(dev.appliedcausality.menu.ProviderBindingMenu::fromNetwork));

    private AppliedCausalityContent() {
    }

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        BLOCK_ENTITY_TYPES.register(modEventBus);
        MENUS.register(modEventBus);
        modEventBus.addListener(AppliedCausalityContent::onCommonSetup);
        modEventBus.addListener(AppliedCausalityContent::onCreativeTab);
    }

    private static void onCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (CreativeModeTabs.FUNCTIONAL_BLOCKS.equals(event.getTabKey())) {
            event.accept(SINGULAR_PATTERN_PROVIDER_ITEM);
            event.accept(CREATIVE_SINGULAR_PATTERN_PROVIDER_ITEM);
        }
    }

    private static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            var block = SINGULAR_PATTERN_PROVIDER.get();
            var blockEntityType = SINGULAR_PATTERN_PROVIDER_BLOCK_ENTITY.get();
            block.setBlockEntity(
                    SingularPatternProviderBlockEntity.class,
                    blockEntityType,
                    null,
                    SingularPatternProviderBlockEntity::serverTick);
            AEBaseBlockEntity.registerBlockEntityItem(blockEntityType, SINGULAR_PATTERN_PROVIDER_ITEM.get());
            var creativeBlock = CREATIVE_SINGULAR_PATTERN_PROVIDER.get();
            var creativeBlockEntityType = CREATIVE_SINGULAR_PATTERN_PROVIDER_BLOCK_ENTITY.get();
            creativeBlock.setBlockEntity(
                    SingularPatternProviderBlockEntity.class,
                    creativeBlockEntityType,
                    null,
                    SingularPatternProviderBlockEntity::serverTick);
            AEBaseBlockEntity.registerBlockEntityItem(creativeBlockEntityType,
                    CREATIVE_SINGULAR_PATTERN_PROVIDER_ITEM.get());
        });
    }
}
