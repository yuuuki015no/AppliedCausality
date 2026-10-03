package dev.appliedcausality.gametest;

import java.lang.reflect.Method;
import java.util.ArrayList;

import appeng.api.networking.crafting.ICraftingPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.pedroksl.advanced_ae.common.cluster.AdvCraftingCPUCalculator;
import net.pedroksl.advanced_ae.common.cluster.AdvCraftingCPUCluster;
import net.pedroksl.advanced_ae.common.entities.AdvCraftingBlockEntity;

import dev.appliedcausality.AppliedCausality;

/** Observation fixtures for unchanged AE2 and AdvancedAE crafting-storage semantics. */
@GameTestHolder(AppliedCausality.MOD_ID)
@PrefixGameTestTemplate(false)
public final class Phase19CraftingStorageGameTests {
    private static final int STANDARD_MAX_AXIS = 17;
    private static final int DEFAULT_QUANTUM_AXIS = 7;
    private static final long MEBIBYTE = 1024L * 1024;
    private static final long QUANTUM_STORAGE_BYTES = 256L * MEBIBYTE;

    private Phase19CraftingStorageGameTests() {
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void standardPlanningBytesExposeStockAdmissionCeilings(GameTestHelper helper) throws Exception {
        var fixture = new Phase11StandardAe2GameTests.Fixture(helper);
        fixture.addSmeltingProvider();

        long representativeCapacity = fixture.cluster.getAvailableStorage();
        require(representativeCapacity == 256 * 1024,
                "representative CPU is not backed by the stock 256 KiB storage block");
        long representativeN = largestMultiplier(representativeCapacity);

        long maximumBlockCount = Math.multiplyExact(
                Math.multiplyExact((long) STANDARD_MAX_AXIS, STANDARD_MAX_AXIS),
                STANDARD_MAX_AXIS);
        long maximumStockCapacity = Math.multiplyExact(maximumBlockCount, representativeCapacity);
        require(maximumBlockCount == 4_913 && maximumStockCapacity == 1_287_913_472L,
                "stock maximum capacity observation changed");
        long maximumStockN = largestMultiplier(maximumStockCapacity);

        assertPlanningBytes(fixture, 1, 19);
        assertPlanningBytes(fixture, representativeN, representativeCapacity);
        assertPlanningBytes(fixture, representativeN + 1, representativeCapacity + 3);
        assertPlanningBytes(fixture, maximumStockN, maximumStockCapacity - 1);
        assertPlanningBytes(fixture, maximumStockN + 1, maximumStockCapacity + 2);

        require(representativeN == 87_376, "representative stock admission boundary changed");
        require(maximumStockN == 429_304_485, "maximum stock admission boundary changed");
        require(maximumStockCapacity < Integer.MAX_VALUE,
                "stock capacity unexpectedly reached the long-sized execution proof range");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void defaultQuantumMaximumCapacityMatchesValidatedStructure(GameTestHelper helper) throws Exception {
        int configuredAxis = advancedConfig("getQuantumComputerMaxSize");
        int configuredEntanglers = advancedConfig("getQuantumComputermaxDataEntanglers");
        int configuredStorageMultiplier = advancedConfig("getQuantumComputerDataEntanglerMultiplication");
        require(configuredAxis == DEFAULT_QUANTUM_AXIS,
                "test runtime does not use the observed default quantum-computer size");
        require(configuredEntanglers == 1,
                "test runtime does not use the observed default data-entangler limit");
        require(configuredStorageMultiplier == 4,
                "test runtime does not use the observed default storage multiplier");

        Block structure = advancedBlock("QUANTUM_STRUCTURE");
        Block coreBlock = advancedBlock("QUANTUM_CORE");
        Block storageBlock = advancedBlock("QUANTUM_STORAGE_256M");
        Block entanglerBlock = advancedBlock("DATA_ENTANGLER");
        var members = new ArrayList<AdvCraftingBlockEntity>(DEFAULT_QUANTUM_AXIS
                * DEFAULT_QUANTUM_AXIS * DEFAULT_QUANTUM_AXIS);
        AdvCraftingBlockEntity core = null;
        AdvCraftingBlockEntity storage = null;
        AdvCraftingBlockEntity entangler = null;
        int interiorIndex = 0;

        BlockPos relativeMin = new BlockPos(1, 2, 1);
        for (int x = 0; x < DEFAULT_QUANTUM_AXIS; x++) {
            for (int y = 0; y < DEFAULT_QUANTUM_AXIS; y++) {
                for (int z = 0; z < DEFAULT_QUANTUM_AXIS; z++) {
                    boolean exterior = x == 0 || y == 0 || z == 0
                            || x == DEFAULT_QUANTUM_AXIS - 1
                            || y == DEFAULT_QUANTUM_AXIS - 1
                            || z == DEFAULT_QUANTUM_AXIS - 1;
                    Block block;
                    if (exterior) {
                        block = structure;
                    } else if (interiorIndex == 0) {
                        block = coreBlock;
                        interiorIndex++;
                    } else if (interiorIndex == 1) {
                        block = entanglerBlock;
                        interiorIndex++;
                    } else {
                        block = storageBlock;
                        interiorIndex++;
                    }

                    BlockPos relative = relativeMin.offset(x, y, z);
                    helper.setBlock(relative, block);
                    BlockEntity blockEntity = helper.getBlockEntity(relative);
                    require(blockEntity instanceof AdvCraftingBlockEntity,
                            "quantum member did not create the expected block entity at " + relative);
                    var member = (AdvCraftingBlockEntity) blockEntity;
                    members.add(member);
                    if (block == coreBlock) {
                        core = member;
                    } else if (block == entanglerBlock) {
                        entangler = member;
                    } else if (block == storageBlock && storage == null) {
                        storage = member;
                    }
                }
            }
        }

        require(core != null && storage != null && entangler != null,
                "maximum quantum fixture is missing a required member");
        require(core.getStorageBytes() == QUANTUM_STORAGE_BYTES
                        && storage.getStorageBytes() == QUANTUM_STORAGE_BYTES,
                "AdvancedAE 256 MiB storage values changed");
        require(entangler.getStorageMultiplier() == 4,
                "AdvancedAE data-entangler multiplier changed");

        BlockPos relativeMax = relativeMin.offset(
                DEFAULT_QUANTUM_AXIS - 1,
                DEFAULT_QUANTUM_AXIS - 1,
                DEFAULT_QUANTUM_AXIS - 1);
        BlockPos first = helper.absolutePos(relativeMin);
        BlockPos second = helper.absolutePos(relativeMax);
        BlockPos absoluteMin = new BlockPos(
                Math.min(first.getX(), second.getX()),
                Math.min(first.getY(), second.getY()),
                Math.min(first.getZ(), second.getZ()));
        BlockPos absoluteMax = new BlockPos(
                Math.max(first.getX(), second.getX()),
                Math.max(first.getY(), second.getY()),
                Math.max(first.getZ(), second.getZ()));
        var calculator = new AdvCraftingCPUCalculator(core);
        require(calculator.checkMultiblockScale(absoluteMin, absoluteMax),
                "default maximum quantum dimensions were rejected");
        require(calculator.verifyInternalStructure(helper.getLevel(), absoluteMin, absoluteMax),
                "default maximum quantum structure was rejected");

        AdvCraftingCPUCluster cluster = calculator.createCluster(helper.getLevel(), absoluteMin, absoluteMax);
        Method addBlockEntity = AdvCraftingCPUCluster.class.getDeclaredMethod(
                "addBlockEntity", AdvCraftingBlockEntity.class);
        addBlockEntity.setAccessible(true);
        for (var member : members) {
            addBlockEntity.invoke(cluster, member);
        }

        long storageBearingMembers = 1 + 123L;
        long expectedCapacity = Math.multiplyExact(
                Math.multiplyExact(storageBearingMembers, QUANTUM_STORAGE_BYTES),
                configuredStorageMultiplier);
        require(expectedCapacity == 133_143_986_176L,
                "default maximum quantum capacity calculation changed");
        require(cluster.getAvailableStorage() == expectedCapacity,
                "AdvancedAE cluster did not aggregate the observed 124 GiB capacity");

        long maximumQuantumN = largestMultiplier(expectedCapacity);
        require(maximumQuantumN == 44_381_328_720L,
                "default maximum quantum admission boundary changed");
        require(requiredBytes(maximumQuantumN) == expectedCapacity
                        && requiredBytes(maximumQuantumN + 1) == expectedCapacity + 3,
                "default maximum quantum boundary no longer follows the observed planning formula");
        helper.succeed();
    }

    private static void assertPlanningBytes(
            Phase11StandardAe2GameTests.Fixture fixture,
            long multiplier,
            long expectedBytes) throws Exception {
        fixture.storage.items.set(fixture.raw, multiplier);
        ICraftingPlan plan = fixture.calculate(fixture.ingot, multiplier);
        require(!plan.simulation(), "representative planning unexpectedly returned a simulation");
        require(plan.bytes() == expectedBytes,
                "planning bytes for N=" + multiplier + " were " + plan.bytes() + ", expected " + expectedBytes);
        require(plan.bytes() == requiredBytes(multiplier),
                "planning bytes no longer match the observed 3N + 16 formula");
    }

    private static long largestMultiplier(long capacity) {
        return Math.floorDiv(Math.subtractExact(capacity, 16), 3);
    }

    private static long requiredBytes(long multiplier) {
        return Math.addExact(Math.multiplyExact(3, multiplier), 16);
    }

    private static Block advancedBlock(String fieldName) throws ReflectiveOperationException {
        var definition = Class.forName("net.pedroksl.advanced_ae.common.definitions.AAEBlocks")
                .getField(fieldName)
                .get(null);
        return (Block) definition.getClass().getMethod("block").invoke(definition);
    }

    private static int advancedConfig(String methodName) throws ReflectiveOperationException {
        Class<?> configClass = Class.forName("net.pedroksl.advanced_ae.common.definitions.AAEConfig");
        Object config = configClass.getMethod("instance").invoke(null);
        return (int) configClass.getMethod(methodName).invoke(config);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
