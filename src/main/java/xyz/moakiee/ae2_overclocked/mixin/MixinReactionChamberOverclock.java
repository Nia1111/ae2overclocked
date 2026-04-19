package xyz.moakiee.ae2_overclocked.mixin;

import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.networking.IGridNode;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.GenericStack;
import xyz.moakiee.ae2_overclocked.Ae2OcConfig;
import xyz.moakiee.ae2_overclocked.support.MENetworkOutputHelper;
import xyz.moakiee.ae2_overclocked.support.OverclockCardRuntime;
import xyz.moakiee.ae2_overclocked.support.ParallelCardRuntime;
import xyz.moakiee.ae2_overclocked.support.ParallelEngine;
import xyz.moakiee.ae2_overclocked.support.ReflectionCache;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;


@Pseudo
@Mixin(targets = "net.pedroksl.advanced_ae.common.entities.ReactionChamberEntity", remap = false)
public abstract class MixinReactionChamberOverclock {
    @Unique
    private boolean ae2oc_processing = false;
    @Unique
    private int ae2oc_prevProcessingTime = -1;
    @Unique
    private int ae2oc_pendingParallel = 0;
    @Unique
    private boolean ae2oc_cachedIsItemOutput = true;
    @Unique
    private ItemStack ae2oc_cachedItemOutput = ItemStack.EMPTY;
    @Unique
    private FluidStack ae2oc_cachedFluidOutput = null;
    @Unique
    private double ae2oc_cachedUnitEnergy = 0;
    @Unique
    private Object ae2oc_cachedRecipe = null;
    @Unique
    private int ae2oc_tickAccumulator = 0;


    @Inject(method = "tickingRequest", at = @At("HEAD"), cancellable = true)
    private void ae2oc_headTick(IGridNode node, int ticksSinceLastCall,
                                 CallbackInfoReturnable<TickRateModulation> cir) {
        Object self = this;
        if (this.ae2oc_processing) {
            return;
        }

        boolean hasOverclock = OverclockCardRuntime.hasOverclockCard(self);
        int parallelMultiplier = ParallelCardRuntime.getParallelMultiplier(self);

        if (!hasOverclock && parallelMultiplier <= 1) {
            return;
        }

        try {
            if (hasOverclock) {
                // Gate craft cycles by configurable tick interval.
                ae2oc_tickAccumulator += ticksSinceLastCall;
                if (ae2oc_tickAccumulator < Ae2OcConfig.getOverclockIntervalTicks()) {
                    cir.setReturnValue(TickRateModulation.URGENT);
                    return;
                }
                ae2oc_tickAccumulator = 0;

                this.ae2oc_processing = true;
                try {
                    ae2oc_instantCraft(self, node, parallelMultiplier);
                } finally {
                    this.ae2oc_processing = false;
                }
                cir.setReturnValue(TickRateModulation.URGENT);
                return;
            }
            Field ptField = ReflectionCache.getFieldHierarchy(self.getClass(), "processingTime");
            if (ptField == null) return;
            this.ae2oc_prevProcessingTime = ptField.getInt(self);
            Method getTask = ReflectionCache.getDeclaredMethod(self.getClass(), "getTask");
            if (getTask == null) return;
            Object recipe = getTask.invoke(self);

            if (recipe != null) {
                Method getEnergy = ReflectionCache.getMethod(recipe.getClass(), "getEnergy");
                if (getEnergy != null) this.ae2oc_cachedUnitEnergy = (int) getEnergy.invoke(recipe);

                Method isItemOutput = ReflectionCache.getMethod(recipe.getClass(), "isItemOutput");
                if (isItemOutput != null) this.ae2oc_cachedIsItemOutput = (boolean) isItemOutput.invoke(recipe);

                if (this.ae2oc_cachedIsItemOutput) {
                    Method getResultItem = ReflectionCache.getMethod(recipe.getClass(), "getResultItem");
                    if (getResultItem != null)
                        this.ae2oc_cachedItemOutput = ((ItemStack) getResultItem.invoke(recipe)).copy();
                } else {
                    Method getResultFluid = ReflectionCache.getMethod(recipe.getClass(), "getResultFluid");
                    if (getResultFluid != null)
                        this.ae2oc_cachedFluidOutput = ((FluidStack) getResultFluid.invoke(recipe)).copy();
                }
                this.ae2oc_cachedRecipe = recipe;
                this.ae2oc_pendingParallel = ae2oc_calculateParallel(
                        self, node, recipe, parallelMultiplier);
            } else {
                this.ae2oc_pendingParallel = 0;
            }

        } catch (Exception e) {
        }
    }


    @Inject(method = "tickingRequest", at = @At("RETURN"))
    private void ae2oc_tailTick(IGridNode node, int ticksSinceLastCall,
                                 CallbackInfoReturnable<TickRateModulation> cir) {
        if (this.ae2oc_pendingParallel <= 1 || this.ae2oc_prevProcessingTime <= 0) {
            ae2oc_resetCache();
            return;
        }

        try {
            Object self = this;

            Field ptField = ReflectionCache.getFieldHierarchy(self.getClass(), "processingTime");
            if (ptField == null) { ae2oc_resetCache(); return; }
            int currentPT = ptField.getInt(self);

            if (currentPT == 0) {
                int extraRounds = this.ae2oc_pendingParallel - 1;
                this.ae2oc_processing = true;
                try {
                    ae2oc_doExtraOutputs(self, node, extraRounds);
                } finally {
                    this.ae2oc_processing = false;
                }
            }
        } catch (Exception e) {
        }

        ae2oc_resetCache();
    }

    @Unique
    private void ae2oc_resetCache() {
        this.ae2oc_pendingParallel = 0;
        this.ae2oc_prevProcessingTime = -1;
        this.ae2oc_cachedItemOutput = ItemStack.EMPTY;
        this.ae2oc_cachedFluidOutput = null;
        this.ae2oc_cachedUnitEnergy = 0;
        this.ae2oc_cachedRecipe = null;
    }


    @Unique
    private void ae2oc_instantCraft(Object self, IGridNode node, int parallelMultiplier) throws Exception {
        ae2oc_handleDirty(self);

        Field outputInvField = ReflectionCache.getFieldHierarchy(self.getClass(), "outputInv");
        if (outputInvField == null) return;
        Object outputInv = outputInvField.get(self);

        Field inputInvField = ReflectionCache.getFieldHierarchy(self.getClass(), "inputInv");
        if (inputInvField == null) return;
        Object inputInv = inputInvField.get(self);

        Field fluidInvField = ReflectionCache.getFieldHierarchy(self.getClass(), "fluidInv");
        if (fluidInvField == null) return;
        Object fluidInv = fluidInvField.get(self);

        var outputTarget = MENetworkOutputHelper.resolveTarget(self, node);
        boolean flushedBeforeCraft = ae2oc_flushLocalOutputsToMENetwork(outputTarget, outputInv, fluidInv);
        if (flushedBeforeCraft) {
            ae2oc_markForUpdate(self);
            ae2oc_saveChanges(self);
        }

        Method getTask = ReflectionCache.getDeclaredMethod(self.getClass(), "getTask");
        if (getTask == null) return;
        Object recipe = getTask.invoke(self);
        if (recipe == null) return;

        Method getEnergy = ReflectionCache.getMethod(recipe.getClass(), "getEnergy");
        if (getEnergy == null) return;
        double unitEnergy = (int) getEnergy.invoke(recipe);

        Method isItemOutput = ReflectionCache.getMethod(recipe.getClass(), "isItemOutput");
        if (isItemOutput == null) return;
        boolean itemOutput = (boolean) isItemOutput.invoke(recipe);

        int craftedRounds;

        if (itemOutput) {
            Method getResultItem = ReflectionCache.getMethod(recipe.getClass(), "getResultItem");
            if (getResultItem == null) return;
            ItemStack outputItem = ((ItemStack) getResultItem.invoke(recipe)).copy();
            if (outputItem.isEmpty()) return;

            Method insertItem = ReflectionCache.getMethod(outputInv.getClass(), "insertItem",
                    int.class, ItemStack.class, boolean.class);
            if (insertItem == null) return;

            craftedRounds = 0;
            for (int round = 0; round < parallelMultiplier; round++) {
                if (!ae2oc_canConsumeRecipeOnce(recipe, inputInv, fluidInv)) {
                    break;
                }
                if (!ae2oc_ensureLocalItemSpace(outputTarget, outputInv, insertItem, outputItem)) {
                    break;
                }
                if (!ae2oc_tryConsumePower(self, node, unitEnergy)) {
                    break;
                }
                if (!ae2oc_consumeRecipeOnce(recipe, inputInv, fluidInv)) {
                    break;
                }

                ItemStack remainder = (ItemStack) insertItem.invoke(outputInv, 0, outputItem.copy(), false);
                if (!remainder.isEmpty()) {
                    break;
                }
                craftedRounds++;
            }
        } else {
            Method getResultFluid = ReflectionCache.getMethod(recipe.getClass(), "getResultFluid");
            if (getResultFluid == null) return;
            FluidStack outputFluid = ((FluidStack) getResultFluid.invoke(recipe)).copy();
            if (outputFluid.isEmpty()) return;
            AEFluidKey fluidKey = AEFluidKey.of(outputFluid);
            if (fluidKey == null) return;

            craftedRounds = 0;
            for (int round = 0; round < parallelMultiplier; round++) {
                if (!ae2oc_canConsumeRecipeOnce(recipe, inputInv, fluidInv)) {
                    break;
                }
                if (!ae2oc_ensureLocalFluidSpace(outputTarget, fluidInv, fluidKey, outputFluid.getAmount())) {
                    break;
                }
                if (!ae2oc_tryConsumePower(self, node, unitEnergy)) {
                    break;
                }
                if (!ae2oc_consumeRecipeOnce(recipe, inputInv, fluidInv)) {
                    break;
                }
                if (!ae2oc_insertFluidLocally(fluidInv, fluidKey, outputFluid.getAmount())) {
                    break;
                }
                craftedRounds++;
            }
        }
        if (craftedRounds < 1) return;

        Field ptField = ReflectionCache.getFieldHierarchy(self.getClass(), "processingTime");
        if (ptField != null) ptField.setInt(self, 0);

        Field cachedTaskField = ReflectionCache.getFieldHierarchy(self.getClass(), "cachedTask");
        if (cachedTaskField != null) cachedTaskField.set(self, null);

        Method setWorking = ReflectionCache.getMethod(self.getClass(), "setWorking", boolean.class);
        if (setWorking != null) setWorking.invoke(self, false);

        ae2oc_markForUpdate(self);
        ae2oc_saveChanges(self);

        var refreshedOutputTarget = MENetworkOutputHelper.resolveTarget(self, node);
        boolean flushedAfterCraft = ae2oc_flushLocalOutputsToMENetwork(refreshedOutputTarget, outputInv, fluidInv);
        if (flushedAfterCraft) {
            ae2oc_markForUpdate(self);
            ae2oc_saveChanges(self);
        }
    }


    @Unique
    private void ae2oc_doExtraOutputs(Object self, IGridNode node, int extraRounds) {
        if (extraRounds <= 0) return;

        try {
            Field inputInvField = ReflectionCache.getFieldHierarchy(self.getClass(), "inputInv");
            if (inputInvField == null) return;
            Object inputInv = inputInvField.get(self);

            Field fluidInvField = ReflectionCache.getFieldHierarchy(self.getClass(), "fluidInv");
            if (fluidInvField == null) return;
            Object fluidInv = fluidInvField.get(self);
            var outputTarget = MENetworkOutputHelper.resolveTarget(self, node);
            int craftedRounds = 0;

            if (this.ae2oc_cachedIsItemOutput) {
                Field outputInvField = ReflectionCache.getFieldHierarchy(self.getClass(), "outputInv");
                if (outputInvField == null) return;
                Object outputInv = outputInvField.get(self);

                Method insertItem = ReflectionCache.getMethod(outputInv.getClass(), "insertItem",
                        int.class, ItemStack.class, boolean.class);
                if (insertItem == null) return;

                for (int round = 0; round < extraRounds; round++) {
                    if (!ae2oc_canConsumeRecipeOnce(this.ae2oc_cachedRecipe, inputInv, fluidInv)) {
                        break;
                    }
                    if (!ae2oc_canStoreItemWithNetworkFallback(outputTarget, outputInv, insertItem, this.ae2oc_cachedItemOutput)) {
                        break;
                    }
                    if (!ae2oc_tryConsumePower(self, node, this.ae2oc_cachedUnitEnergy)) {
                        break;
                    }
                    if (!ae2oc_consumeRecipeOnce(this.ae2oc_cachedRecipe, inputInv, fluidInv)) {
                        break;
                    }
                    ae2oc_insertItemWithNetworkFallback(outputTarget, outputInv, insertItem, this.ae2oc_cachedItemOutput.copy(), false);
                    craftedRounds++;
                }

                if (craftedRounds > 0) {
                    ae2oc_transferItemOutputToNetwork(outputTarget, outputInv);
                }
            } else {
                AEFluidKey fluidKey = AEFluidKey.of(this.ae2oc_cachedFluidOutput);
                if (fluidKey == null) return;

                for (int round = 0; round < extraRounds; round++) {
                    if (!ae2oc_canConsumeRecipeOnce(this.ae2oc_cachedRecipe, inputInv, fluidInv)) {
                        break;
                    }
                    if (!ae2oc_canStoreFluidWithNetworkFallback(
                            outputTarget, fluidInv, fluidKey, this.ae2oc_cachedFluidOutput.getAmount())) {
                        break;
                    }
                    if (!ae2oc_tryConsumePower(self, node, this.ae2oc_cachedUnitEnergy)) {
                        break;
                    }
                    if (!ae2oc_consumeRecipeOnce(this.ae2oc_cachedRecipe, inputInv, fluidInv)) {
                        break;
                    }
                    ae2oc_insertFluidWithNetworkFallback(
                            outputTarget, fluidInv, fluidKey, this.ae2oc_cachedFluidOutput.getAmount(), false);
                    craftedRounds++;
                }

                if (craftedRounds > 0) {
                    ae2oc_transferFluidOutputToNetwork(outputTarget, fluidInv);
                }
            }

            if (craftedRounds > 0) {
                ae2oc_saveChanges(self);
            }

        } catch (Exception e) {
        }
    }

    @Unique
    private void ae2oc_handleDirty(Object self) {
        try {
            Field dirtyField = ReflectionCache.getFieldHierarchy(self.getClass(), "dirty");
            if (dirtyField == null) return;
            boolean dirty = dirtyField.getBoolean(self);

            if (dirty) {
                Field levelField = ReflectionCache.getFieldHierarchy(self.getClass(), "level");
                Object level = levelField != null ? levelField.get(self) : null;

                if (level != null) {
                    Method findRecipe = ReflectionCache.getDeclaredMethod(self.getClass(), "findRecipe",
                            net.minecraft.world.level.Level.class);
                    if (findRecipe != null) {
                        Object recipe = findRecipe.invoke(self, level);
                        if (recipe == null) {
                            Field ptField = ReflectionCache.getFieldHierarchy(self.getClass(), "processingTime");
                            if (ptField != null) ptField.setInt(self, 0);

                            Method setWorking = ReflectionCache.getMethod(self.getClass(), "setWorking", boolean.class);
                            if (setWorking != null) setWorking.invoke(self, false);

                            Field cachedTaskField = ReflectionCache.getFieldHierarchy(self.getClass(), "cachedTask");
                            if (cachedTaskField != null) cachedTaskField.set(self, null);
                        }
                    }
                }
                ae2oc_markForUpdate(self);
                dirtyField.setBoolean(self, false);
            }
        } catch (Exception ignored) {
        }
    }

    @Unique
    private int ae2oc_calculateParallel(Object self, IGridNode node, Object recipe, int cardMultiplier) {
        try {
            Field inputInvField = ReflectionCache.getFieldHierarchy(self.getClass(), "inputInv");
            if (inputInvField == null) return 1;
            Object inputInv = inputInvField.get(self);

            Field fluidInvField = ReflectionCache.getFieldHierarchy(self.getClass(), "fluidInv");
            if (fluidInvField == null) return 1;
            Object fluidInv = fluidInvField.get(self);

            int materialLimit = ae2oc_calculateInputRounds(recipe, inputInv, fluidInv, cardMultiplier);
            if (materialLimit <= 0) {
                return 0;
            }
            double availableEnergy = ae2oc_getAvailableEnergy(self, node);
            var outputTarget = MENetworkOutputHelper.resolveTarget(self, node);

            Method getEnergy = ReflectionCache.getMethod(recipe.getClass(), "getEnergy");
            if (getEnergy == null) return 1;
            double unitEnergy = (int) getEnergy.invoke(recipe);

            Method isItemOutput = ReflectionCache.getMethod(recipe.getClass(), "isItemOutput");
            if (isItemOutput == null) return 1;
            boolean itemOutput = (boolean) isItemOutput.invoke(recipe);

            if (itemOutput) {
                Field outputInvField = ReflectionCache.getFieldHierarchy(self.getClass(), "outputInv");
                if (outputInvField == null) return 1;
                Object outputInv = outputInvField.get(self);

                Method getResultItem = ReflectionCache.getMethod(recipe.getClass(), "getResultItem");
                if (getResultItem == null) return 1;
                ItemStack outputItem = ((ItemStack) getResultItem.invoke(recipe)).copy();

                Method insertItem = ReflectionCache.getMethod(outputInv.getClass(), "insertItem",
                        int.class, ItemStack.class, boolean.class);
                if (insertItem == null) return 1;

                return ParallelEngine.calculate(
                        cardMultiplier, materialLimit, 1, outputItem,
                        (stack, simulate) -> ae2oc_insertItemWithNetworkFallback(outputTarget, outputInv, insertItem, stack, simulate),
                        availableEnergy, unitEnergy
                ).actualParallel();
            } else {
                Method getResultFluid = ReflectionCache.getMethod(recipe.getClass(), "getResultFluid");
                if (getResultFluid == null) return 1;
                FluidStack outputFluid = (FluidStack) getResultFluid.invoke(recipe);
                int fluidOutputLimit = ae2oc_getFluidOutputLimit(outputTarget, fluidInv, outputFluid, cardMultiplier);

                return ParallelEngine.calculateSimple(
                        cardMultiplier, materialLimit, 1,
                        fluidOutputLimit, availableEnergy, unitEnergy
                ).actualParallel();
            }
        } catch (Exception e) {
            return 1;
        }
    }

    @Unique
    private int ae2oc_calculateInputRounds(Object recipe, Object inputInv, Object fluidInv, int maxRounds) {
        if (maxRounds <= 0) {
            return 0;
        }

        try {
            ItemStack[] itemStacks = ae2oc_snapshotInputItems(inputInv);
            FluidStack[] fluidHolder = new FluidStack[]{ae2oc_snapshotInputFluid(fluidInv)};

            int rounds = 0;
            while (rounds < maxRounds && ae2oc_consumeRecipeOnce(recipe, itemStacks, fluidHolder)) {
                rounds++;
            }
            return rounds;
        } catch (Exception e) {
            return 0;
        }
    }

    @Unique
    private boolean ae2oc_canConsumeRecipeOnce(Object recipe, Object inputInv, Object fluidInv) {
        return ae2oc_calculateInputRounds(recipe, inputInv, fluidInv, 1) > 0;
    }

    @Unique
    private boolean ae2oc_consumeRecipeOnce(Object recipe, Object inputInv, Object fluidInv) {
        try {
            ItemStack[] itemStacks = ae2oc_snapshotInputItems(inputInv);
            FluidStack[] fluidHolder = new FluidStack[]{ae2oc_snapshotInputFluid(fluidInv)};
            if (!ae2oc_consumeRecipeOnce(recipe, itemStacks, fluidHolder)) {
                return false;
            }

            ae2oc_writeInputItems(inputInv, itemStacks);
            ae2oc_writeInputFluid(fluidInv, fluidHolder[0]);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Unique
    private boolean ae2oc_consumeRecipeOnce(Object recipe, ItemStack[] itemStacks, FluidStack[] fluidHolder) throws Exception {
        for (Object input : ae2oc_getValidInputs(recipe)) {
            Method checkType = ReflectionCache.getMethod(input.getClass(), "checkType", Object.class);
            Method consume = ReflectionCache.getMethod(input.getClass(), "consume", Object.class);
            Method isEmpty = ReflectionCache.getMethod(input.getClass(), "isEmpty");
            if (checkType == null || consume == null || isEmpty == null) {
                return false;
            }

            for (ItemStack stack : itemStacks) {
                if ((boolean) checkType.invoke(input, stack)) {
                    consume.invoke(input, stack);
                }
                if ((boolean) isEmpty.invoke(input)) {
                    break;
                }
            }

            FluidStack fluidStack = fluidHolder[0];
            if (fluidStack != null
                    && !fluidStack.isEmpty()
                    && !(boolean) isEmpty.invoke(input)
                    && (boolean) checkType.invoke(input, fluidStack)) {
                consume.invoke(input, fluidStack);
            }

            if (!(boolean) isEmpty.invoke(input)) {
                return false;
            }
        }
        return true;
    }

    @Unique
    private static final int AE2OC_MAX_PARALLEL_LIMIT = 1_000_000_000;

    @Unique
    private int ae2oc_getFluidOutputLimit(MENetworkOutputHelper.OutputTarget outputTarget, Object fluidInv, FluidStack outputFluid,
                                          int maxParallel) {
        try {
            AEFluidKey fluidKey = AEFluidKey.of(outputFluid);
            if (fluidKey == null) return 0;
            Method canAdd = ReflectionCache.getMethod(fluidInv.getClass(), "canAdd", int.class, AEFluidKey.class, int.class);
            int safeMaxParallel = Math.min(maxParallel, AE2OC_MAX_PARALLEL_LIMIT);

            int lo = 0, hi = safeMaxParallel;
            while (lo < hi) {
                int mid = lo + (hi - lo + 1) / 2;
                long totalAmount = (long) outputFluid.getAmount() * mid;
                long insertedToNetwork = MENetworkOutputHelper.tryInsert(outputTarget, fluidKey, totalAmount, Actionable.SIMULATE);
                long remainder = totalAmount - insertedToNetwork;
                if (remainder <= 0) {
                    lo = mid;
                    continue;
                }
                if (remainder > Integer.MAX_VALUE || canAdd == null) {
                    hi = mid - 1;
                    continue;
                }
                if ((boolean) canAdd.invoke(fluidInv, 0, fluidKey, (int) remainder)) {
                    lo = mid;
                } else {
                    hi = mid - 1;
                }
            }
            return lo;
        } catch (Exception e) {
            return 0;
        }
    }

    @Unique
    private ItemStack ae2oc_insertItemWithNetworkFallback(MENetworkOutputHelper.OutputTarget outputTarget, Object outputInv,
                                                          Method insertItem, ItemStack stack, boolean simulate) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }

        try {
            Actionable mode = simulate ? Actionable.SIMULATE : Actionable.MODULATE;
            int insertedToNetwork = MENetworkOutputHelper.tryInsertItem(outputTarget, stack, mode);
            if (insertedToNetwork >= stack.getCount()) {
                return ItemStack.EMPTY;
            }

            ItemStack remainder = stack.copy();
            remainder.setCount(stack.getCount() - insertedToNetwork);
            return (ItemStack) insertItem.invoke(outputInv, 0, remainder, simulate);
        } catch (Exception e) {
            return stack;
        }
    }

    @Unique
    private int ae2oc_insertFluidWithNetworkFallback(MENetworkOutputHelper.OutputTarget outputTarget, Object fluidInv, AEFluidKey fluidKey,
                                                     int amount, boolean simulate) {
        if (fluidKey == null || amount <= 0) {
            return 0;
        }

        try {
            Actionable mode = simulate ? Actionable.SIMULATE : Actionable.MODULATE;
            long insertedToNetwork = MENetworkOutputHelper.tryInsert(outputTarget, fluidKey, amount, mode);
            long remainder = amount - insertedToNetwork;
            if (remainder <= 0) {
                return amount;
            }

            if (remainder > Integer.MAX_VALUE) {
                return (int) insertedToNetwork;
            }

            Method canAdd = ReflectionCache.getMethod(fluidInv.getClass(), "canAdd", int.class, AEFluidKey.class, int.class);
            if (canAdd == null || !(boolean) canAdd.invoke(fluidInv, 0, fluidKey, (int) remainder)) {
                return (int) insertedToNetwork;
            }

            if (!simulate) {
                Method addMethod = ReflectionCache.getMethod(fluidInv.getClass(), "add", int.class, AEFluidKey.class, int.class);
                if (addMethod == null) {
                    return (int) insertedToNetwork;
                }
                addMethod.invoke(fluidInv, 0, fluidKey, (int) remainder);
            }

            return amount;
        } catch (Exception e) {
            return 0;
        }
    }

    @Unique
    private List<Object> ae2oc_getValidInputs(Object recipe) throws Exception {
        Method getValidInputs = ReflectionCache.getMethod(recipe.getClass(), "getValidInputs");
        if (getValidInputs == null) {
            return List.of();
        }

        @SuppressWarnings("unchecked")
        List<Object> validInputs = (List<Object>) getValidInputs.invoke(recipe);
        return validInputs;
    }

    @Unique
    private double ae2oc_getAvailableEnergy(Object self, IGridNode node) {
        double total = 0;
        try {
            Method extractMethod = ReflectionCache.getMethod(self.getClass(),
                    "extractAEPower", double.class, Actionable.class, PowerMultiplier.class);
            if (extractMethod != null) {
                total += (double) extractMethod.invoke(self, Double.MAX_VALUE,
                        Actionable.SIMULATE, PowerMultiplier.CONFIG);
            }

            var grid = node.getGrid();
            if (grid != null) {
                IEnergyService energyService = grid.getEnergyService();
                total += energyService.extractAEPower(Double.MAX_VALUE, Actionable.SIMULATE, PowerMultiplier.CONFIG);
            }
        } catch (Exception e) {
        }
        return total;
    }

    @Unique
    private boolean ae2oc_tryConsumePower(Object self, IGridNode node, double powerNeeded) {
        try {
            Method extractMethod = ReflectionCache.getMethod(self.getClass(),
                    "extractAEPower", double.class, Actionable.class, PowerMultiplier.class);
            if (extractMethod == null) return false;

            double extracted = (double) extractMethod.invoke(self, powerNeeded,
                    Actionable.SIMULATE, PowerMultiplier.CONFIG);
            if (extracted >= powerNeeded - 0.01) {
                extractMethod.invoke(self, powerNeeded, Actionable.MODULATE, PowerMultiplier.CONFIG);
                return true;
            }

            var grid = node.getGrid();
            if (grid != null) {
                IEnergyService energyService = grid.getEnergyService();
                double networkExtracted = energyService.extractAEPower(powerNeeded, Actionable.SIMULATE,
                        PowerMultiplier.CONFIG);
                if (networkExtracted >= powerNeeded - 0.01) {
                    energyService.extractAEPower(powerNeeded, Actionable.MODULATE, PowerMultiplier.CONFIG);
                    return true;
                }
            }
        } catch (Exception e) {
        }
        return false;
    }

    @Unique
    private void ae2oc_markForUpdate(Object self) {
        try {
            Method method = ReflectionCache.getMethod(self.getClass(), "markForUpdate");
            if (method != null) method.invoke(self);
        } catch (Exception ignored) {
        }
    }

    @Unique
    private void ae2oc_saveChanges(Object self) {
        try {
            Method method = ReflectionCache.getMethod(self.getClass(), "saveChanges");
            if (method != null) method.invoke(self);
        } catch (Exception ignored) {
        }
    }

    @Unique
    private ItemStack[] ae2oc_snapshotInputItems(Object inputInv) throws Exception {
        Method sizeMethod = ReflectionCache.getMethod(inputInv.getClass(), "size");
        Method getStackInSlot = ReflectionCache.getMethod(inputInv.getClass(), "getStackInSlot", int.class);
        if (sizeMethod == null || getStackInSlot == null) {
            return new ItemStack[0];
        }

        int size = (int) sizeMethod.invoke(inputInv);
        ItemStack[] snapshot = new ItemStack[size];
        for (int slot = 0; slot < size; slot++) {
            snapshot[slot] = ((ItemStack) getStackInSlot.invoke(inputInv, slot)).copy();
        }
        return snapshot;
    }

    @Unique
    private FluidStack ae2oc_snapshotInputFluid(Object fluidInv) throws Exception {
        Method getStack = ReflectionCache.getMethod(fluidInv.getClass(), "getStack", int.class);
        if (getStack == null) {
            return null;
        }

        Object gs = getStack.invoke(fluidInv, 1);
        if (gs == null) {
            return null;
        }

        Field whatField = ReflectionCache.getField(gs.getClass(), "what");
        Field amountField = ReflectionCache.getField(gs.getClass(), "amount");
        if (whatField == null || amountField == null) {
            return null;
        }

        Object aeKey = whatField.get(gs);
        long amount = amountField.getLong(gs);
        if (!(aeKey instanceof AEFluidKey key) || amount <= 0) {
            return null;
        }
        return key.toStack((int) amount);
    }

    @Unique
    private void ae2oc_writeInputItems(Object inputInv, ItemStack[] itemStacks) throws Exception {
        Method setItemDirect = ReflectionCache.getMethod(inputInv.getClass(), "setItemDirect", int.class, ItemStack.class);
        if (setItemDirect == null) {
            return;
        }

        for (int slot = 0; slot < itemStacks.length; slot++) {
            setItemDirect.invoke(inputInv, slot, itemStacks[slot]);
        }
    }

    @Unique
    private void ae2oc_writeInputFluid(Object fluidInv, FluidStack fluidStack) throws Exception {
        Method setStack = ReflectionCache.getMethod(fluidInv.getClass(), "setStack", int.class, GenericStack.class);
        if (setStack == null) {
            return;
        }

        if (fluidStack == null || fluidStack.isEmpty()) {
            setStack.invoke(fluidInv, 1, null);
            return;
        }

        AEFluidKey fluidKey = AEFluidKey.of(fluidStack);
        if (fluidKey == null) {
            setStack.invoke(fluidInv, 1, null);
            return;
        }

        setStack.invoke(fluidInv, 1, new GenericStack(fluidKey, fluidStack.getAmount()));
    }

    @Unique
    private boolean ae2oc_canStoreItemWithNetworkFallback(MENetworkOutputHelper.OutputTarget outputTarget, Object outputInv,
                                                          Method insertItem, ItemStack stack) {
        return ae2oc_insertItemWithNetworkFallback(outputTarget, outputInv, insertItem, stack.copy(), true).isEmpty();
    }

    @Unique
    private boolean ae2oc_canStoreFluidWithNetworkFallback(MENetworkOutputHelper.OutputTarget outputTarget,
                                                           Object fluidInv, AEFluidKey fluidKey, int amount) {
        return ae2oc_insertFluidWithNetworkFallback(outputTarget, fluidInv, fluidKey, amount, true) >= amount;
    }

    @Unique
    private boolean ae2oc_ensureLocalItemSpace(MENetworkOutputHelper.OutputTarget outputTarget, Object outputInv,
                                               Method insertItem, ItemStack stack) {
        try {
            if (((ItemStack) insertItem.invoke(outputInv, 0, stack.copy(), true)).isEmpty()) {
                return true;
            }

            ae2oc_transferItemOutputToNetwork(outputTarget, outputInv);
            return ((ItemStack) insertItem.invoke(outputInv, 0, stack.copy(), true)).isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    @Unique
    private boolean ae2oc_ensureLocalFluidSpace(MENetworkOutputHelper.OutputTarget outputTarget, Object fluidInv,
                                                AEFluidKey fluidKey, int amount) {
        try {
            Method canAdd = ReflectionCache.getMethod(fluidInv.getClass(), "canAdd", int.class, AEFluidKey.class, int.class);
            if (canAdd == null) {
                return false;
            }

            if ((boolean) canAdd.invoke(fluidInv, 0, fluidKey, amount)) {
                return true;
            }

            ae2oc_transferFluidOutputToNetwork(outputTarget, fluidInv);
            return (boolean) canAdd.invoke(fluidInv, 0, fluidKey, amount);
        } catch (Exception e) {
            return false;
        }
    }

    @Unique
    private boolean ae2oc_insertFluidLocally(Object fluidInv, AEFluidKey fluidKey, int amount) {
        try {
            Method addMethod = ReflectionCache.getMethod(fluidInv.getClass(), "add", int.class, AEFluidKey.class, int.class);
            if (addMethod == null) {
                return false;
            }

            Object inserted = addMethod.invoke(fluidInv, 0, fluidKey, amount);
            return inserted instanceof Integer value && value >= amount;
        } catch (Exception e) {
            return false;
        }
    }

    @Unique
    private boolean ae2oc_flushLocalOutputsToMENetwork(MENetworkOutputHelper.OutputTarget outputTarget,
                                                       Object outputInv, Object fluidInv) {
        boolean changed = false;
        changed |= ae2oc_transferItemOutputToNetwork(outputTarget, outputInv);
        changed |= ae2oc_transferFluidOutputToNetwork(outputTarget, fluidInv);
        return changed;
    }


    @Unique
    private boolean ae2oc_transferItemOutputToNetwork(MENetworkOutputHelper.OutputTarget outputTarget, Object outputInv) {
        try {
            Method getStackInSlot = ReflectionCache.getMethod(outputInv.getClass(), "getStackInSlot", int.class);
            Method setItemDirect = ReflectionCache.getMethod(outputInv.getClass(), "setItemDirect", int.class, ItemStack.class);
            if (getStackInSlot == null || setItemDirect == null) return false;
            ItemStack stack = (ItemStack) getStackInSlot.invoke(outputInv, 0);
            if (stack.isEmpty()) return false;

            int inserted = MENetworkOutputHelper.tryInsertItem(outputTarget, stack, Actionable.MODULATE);
            if (inserted >= stack.getCount()) {
                setItemDirect.invoke(outputInv, 0, ItemStack.EMPTY);
                return true;
            } else if (inserted > 0) {
                stack.shrink(inserted);
                setItemDirect.invoke(outputInv, 0, stack);
                return true;
            }

        } catch (Exception e) {
        }
        return false;
    }


    @Unique
    private boolean ae2oc_transferFluidOutputToNetwork(MENetworkOutputHelper.OutputTarget outputTarget, Object fluidInv) {
        try {
            Method getStack = ReflectionCache.getMethod(fluidInv.getClass(), "getStack", int.class);
            Method setStack = ReflectionCache.getMethod(fluidInv.getClass(), "setStack", int.class, GenericStack.class);
            if (getStack == null || setStack == null) return false;
            Object gs = getStack.invoke(fluidInv, 0);
            if (gs == null) return false;

            Field whatField = ReflectionCache.getField(gs.getClass(), "what");
            Field amountField = ReflectionCache.getField(gs.getClass(), "amount");
            if (whatField == null || amountField == null) return false;
            Object aeKey = whatField.get(gs);
            long amount = amountField.getLong(gs);

            if (!(aeKey instanceof AEFluidKey fluidKey) || amount <= 0) return false;

            long inserted = MENetworkOutputHelper.tryInsert(outputTarget, fluidKey, amount, Actionable.MODULATE);
            if (inserted >= amount) {
                setStack.invoke(fluidInv, 0, null);
                return true;
            } else if (inserted > 0) {
                setStack.invoke(fluidInv, 0, new GenericStack(fluidKey, amount - inserted));
                return true;
            }

        } catch (Exception e) {
        }
        return false;
    }
}
