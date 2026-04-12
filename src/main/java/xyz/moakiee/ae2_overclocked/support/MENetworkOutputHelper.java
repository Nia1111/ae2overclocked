package xyz.moakiee.ae2_overclocked.support;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

public final class MENetworkOutputHelper {

    public record OutputTarget(IGridNode node, IStorageService storage, IActionSource actionSource) {
        public boolean isAvailable() {
            return this.storage != null;
        }
    }

    private MENetworkOutputHelper() {
    }

    public static int tryInsertItem(Object sourceCandidate, IGridNode fallbackNode, ItemStack stack, Actionable mode) {
        return tryInsertItem(resolveTarget(sourceCandidate, fallbackNode), stack, mode);
    }

    public static int tryInsertItem(OutputTarget target, ItemStack stack, Actionable mode) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }

        AEItemKey key = AEItemKey.of(stack);
        if (key == null) {
            return 0;
        }

        return clampToInt(tryInsert(target, key, stack.getCount(), mode));
    }

    public static long tryInsertFluid(Object sourceCandidate, IGridNode fallbackNode, FluidStack stack, Actionable mode) {
        return tryInsertFluid(resolveTarget(sourceCandidate, fallbackNode), stack, mode);
    }

    public static long tryInsertFluid(OutputTarget target, FluidStack stack, Actionable mode) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }

        AEFluidKey key = AEFluidKey.of(stack);
        if (key == null) {
            return 0;
        }

        return tryInsert(target, key, stack.getAmount(), mode);
    }

    public static long tryInsert(Object sourceCandidate, IGridNode fallbackNode, AEKey key, long amount, Actionable mode) {
        return tryInsert(resolveTarget(sourceCandidate, fallbackNode), key, amount, mode);
    }

    public static long tryInsert(OutputTarget target, AEKey key, long amount, Actionable mode) {
        if (key == null || amount <= 0) {
            return 0;
        }

        if (target == null || !target.isAvailable()) {
            return 0;
        }

        try {
            return Math.max(target.storage().getInventory().insert(
                    key,
                    amount,
                    mode,
                    target.actionSource()
            ), 0);
        } catch (Exception ignored) {
            return 0;
        }
    }

    public static boolean canAcceptAll(Object sourceCandidate, IGridNode fallbackNode, List<GenericStack> stacks) {
        return canAcceptAll(resolveTarget(sourceCandidate, fallbackNode), stacks);
    }

    public static boolean canAcceptAll(OutputTarget target, List<GenericStack> stacks) {
        if (stacks == null || stacks.isEmpty()) {
            return true;
        }

        for (GenericStack stack : stacks) {
            if (stack == null || stack.what() == null || stack.amount() <= 0) {
                continue;
            }
            if (tryInsert(target, stack.what(), stack.amount(), Actionable.SIMULATE)
                    < stack.amount()) {
                return false;
            }
        }
        return true;
    }

    public static IActionSource resolveActionSource(Object sourceCandidate, IGridNode fallbackNode) {
        return resolveActionSourceWithNode(sourceCandidate, resolveGridNode(sourceCandidate, fallbackNode));
    }

    public static OutputTarget resolveTarget(Object sourceCandidate, IGridNode fallbackNode) {
        IGridNode node = resolveGridNode(sourceCandidate, fallbackNode);
        return new OutputTarget(node, resolveStorageService(node), resolveActionSourceWithNode(sourceCandidate, node));
    }

    private static IActionSource resolveActionSourceWithNode(Object sourceCandidate, IGridNode resolvedNode) {
        IActionSource reflected = resolveActionSourceDirect(sourceCandidate);
        if (reflected != null) {
            return reflected;
        }

        if (resolvedNode != null && resolvedNode.getOwner() instanceof IActionHost actionHost) {
            return IActionSource.ofMachine(actionHost);
        }

        return IActionSource.empty();
    }

    public static IStorageService resolveStorageService(Object sourceCandidate, IGridNode fallbackNode) {
        return resolveStorageService(resolveGridNode(sourceCandidate, fallbackNode));
    }

    public static IStorageService resolveStorageService(IGridNode node) {
        if (node == null) {
            return null;
        }

        IGrid grid = node.getGrid();
        if (grid == null) {
            return null;
        }

        return grid.getService(IStorageService.class);
    }

    public static IGridNode resolveGridNode(Object sourceCandidate, IGridNode fallbackNode) {
        IGridNode resolved = resolveGridNodeInternal(sourceCandidate, 0);
        return resolved != null ? resolved : fallbackNode;
    }

    private static IGridNode resolveGridNodeInternal(Object candidate, int depth) {
        if (candidate == null || depth > 3) {
            return null;
        }

        if (candidate instanceof IGridNode node) {
            return node;
        }

        if (candidate instanceof IManagedGridNode managedGridNode) {
            return managedGridNode.getNode();
        }

        if (candidate instanceof IActionHost actionHost) {
            IGridNode actionableNode = actionHost.getActionableNode();
            if (actionableNode != null) {
                return actionableNode;
            }
        }

        try {
            Method getActionableNode = ReflectionCache.getMethod(candidate.getClass(), "getActionableNode");
            if (getActionableNode != null) {
                Object actionableNode = getActionableNode.invoke(candidate);
                IGridNode resolved = resolveGridNodeInternal(actionableNode, depth + 1);
                if (resolved != null) {
                    return resolved;
                }
            }
        } catch (Exception ignored) {
        }

        try {
            Method getMainNode = ReflectionCache.getMethod(candidate.getClass(), "getMainNode");
            if (getMainNode != null) {
                Object mainNode = getMainNode.invoke(candidate);
                IGridNode resolved = resolveGridNodeInternal(mainNode, depth + 1);
                if (resolved != null) {
                    return resolved;
                }
            }
        } catch (Exception ignored) {
        }

        try {
            Method getNode = ReflectionCache.getMethod(candidate.getClass(), "getNode");
            if (getNode != null) {
                Object node = getNode.invoke(candidate);
                return resolveGridNodeInternal(node, depth + 1);
            }
        } catch (Exception ignored) {
        }

        return null;
    }

    private static IActionSource resolveActionSourceDirect(Object candidate) {
        if (candidate == null) {
            return null;
        }

        if (candidate instanceof IActionSource actionSource) {
            return actionSource;
        }

        if (candidate instanceof IActionHost actionHost) {
            return IActionSource.ofMachine(actionHost);
        }

        try {
            Method getActionSource = ReflectionCache.getMethod(candidate.getClass(), "getActionSource");
            if (getActionSource != null) {
                Object actionSource = getActionSource.invoke(candidate);
                if (actionSource instanceof IActionSource resolved) {
                    return resolved;
                }
            }
        } catch (Exception ignored) {
        }

        try {
            Field actionSourceField = ReflectionCache.getFieldHierarchy(candidate.getClass(), "actionSource");
            if (actionSourceField != null) {
                Object actionSource = actionSourceField.get(candidate);
                if (actionSource instanceof IActionSource resolved) {
                    return resolved;
                }
            }
        } catch (Exception ignored) {
        }

        return null;
    }

    private static int clampToInt(long value) {
        if (value <= 0) {
            return 0;
        }
        return (int) Math.min(value, Integer.MAX_VALUE);
    }
}
