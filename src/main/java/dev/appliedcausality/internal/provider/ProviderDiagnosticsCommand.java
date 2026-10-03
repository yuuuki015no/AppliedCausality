package dev.appliedcausality.internal.provider;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import dev.appliedcausality.blockentity.SingularPatternProviderBlockEntity;
import dev.appliedcausality.internal.config.ConfiguredRuleService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.TagKey;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.IEventBus;

import java.util.Optional;

/** Operator-only, read-only diagnostics for rule authors and placed Providers. */
public final class ProviderDiagnosticsCommand {
    private ProviderDiagnosticsCommand() {
    }

    public static void install(IEventBus forgeBus) {
        forgeBus.addListener(ProviderDiagnosticsCommand::register);
    }

    private static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("appliedcausality")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("diagnostics")
                        .then(Commands.literal("rules")
                                .executes(context -> rules(context, 1))
                                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                        .executes(context -> rules(context,
                                                IntegerArgumentType.getInteger(context, "page")))))
                        .then(Commands.literal("type")
                                .then(Commands.argument("recipe_type", ResourceLocationArgument.id())
                                        .executes(context -> recipeType(context, 1))
                                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                                .executes(context -> recipeType(context,
                                                        IntegerArgumentType.getInteger(context, "page"))))))
                        .then(Commands.literal("provider")
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .executes(context -> provider(context, 1))
                                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                                .executes(context -> provider(context,
                                                        IntegerArgumentType.getInteger(context, "page"))))))));
    }

    private static int rules(CommandContext<CommandSourceStack> context, int page) {
        return send(context, ProviderAuthorDiagnostics.rules(
                ConfiguredRuleService.snapshot(context.getSource().getServer()),
                ConfiguredRuleService.lastRejectedDiagnostics(context.getSource().getServer()),
                tagId -> BuiltInRegistries.ITEM.getTag(TagKey.create(Registries.ITEM, tagId))
                        .map(holders -> holders.iterator().hasNext()).orElse(false), page));
    }

    private static int recipeType(CommandContext<CommandSourceStack> context, int page) {
        return send(context, ProviderAuthorDiagnostics.recipeType(
                ConfiguredRuleService.snapshot(context.getSource().getServer()),
                ResourceLocationArgument.getId(context, "recipe_type"), page));
    }

    private static int provider(CommandContext<CommandSourceStack> context, int page)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var pos = BlockPosArgument.getLoadedBlockPos(context, "pos");
        if (!(context.getSource().getLevel().getBlockEntity(pos)
                instanceof SingularPatternProviderBlockEntity provider)) {
            context.getSource().sendFailure(Component.literal("No Applied Causality Provider at " + pos));
            return 0;
        }
        return send(context, ProviderAuthorDiagnostics.provider(provider.isCreative(),
                provider.getPatternStatuses(), ConfiguredRuleService.snapshot(context.getSource().getServer()),
                provider.installedGrants(), page));
    }

    private static int send(CommandContext<CommandSourceStack> context,
            Optional<ProviderAuthorDiagnostics.Page> page) {
        if (page.isEmpty()) {
            context.getSource().sendFailure(Component.literal("Diagnostics page out of range"));
            return 0;
        }
        for (String line : page.orElseThrow().lines()) {
            context.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        return page.orElseThrow().lines().size();
    }
}
