package dev.appliedcausality.internal.provider;

import dev.appliedcausality.internal.config.ConfiguredRuleSnapshot;
import dev.appliedcausality.internal.config.ConfiguredUnlockPolicy;
import net.minecraft.resources.ResourceLocation;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/** Read-only, paged explanations for pack authors. Never used to authorize execution. */
public final class ProviderAuthorDiagnostics {
    public static final int MAX_LINE_BYTES = 512;
    public static final int MAX_PAGE_BYTES = 4 * 1024;
    public static final int PAGE_CONTENT_LINES = 12;
    private static final int PAGE_HEADER_RESERVE_BYTES = 64;

    private ProviderAuthorDiagnostics() {
    }

    public static Optional<Page> rules(ConfiguredRuleSnapshot rules, List<String> rejected,
            Predicate<ResourceLocation> populatedItemTag, int page) {
        Objects.requireNonNull(rules);
        Objects.requireNonNull(rejected);
        Objects.requireNonNull(populatedItemTag);
        var lines = new ArrayList<String>();
        lines.add("Rules generation " + rules.generation() + ": " + rules.recipeTypes().size()
                + " configured Recipe Types, " + rules.unlockRules().size() + " Unlock Rules, "
                + rules.installers().size() + " Installers; normal default "
                + (rules.defaultAllow() ? "ALLOW_ALL" : "DENY_ALL"));
        for (String error : rejected) {
            lines.add("Last rejected reload: " + error);
        }
        for (var profile : rules.recipeTypes().values()) {
            lines.add("Type " + profile.recipeType() + " <- profile " + profile.id()
                    + "; exclusions " + profile.excludedRecipes().size());
        }
        for (var installer : rules.installers().values()) {
            if (installer.itemTag() != null && !populatedItemTag.test(installer.itemTag())) {
                lines.add("Missing or empty Installer item tag " + installer.itemTag()
                        + " in " + installer.id() + " (grants nothing)");
            }
        }
        return paginate(lines, page);
    }

    public static Optional<Page> recipeType(ConfiguredRuleSnapshot rules, ResourceLocation recipeType, int page) {
        Objects.requireNonNull(rules);
        Objects.requireNonNull(recipeType);
        var profile = rules.recipeTypes().get(recipeType);
        if (profile == null) {
            return paginate(List.of("Recipe Type " + recipeType + " is not configured"), page);
        }
        var lines = new ArrayList<String>();
        lines.add("Recipe Type " + recipeType + " trusts configured standard_static_item profile " + profile.id()
                + "; shape " + profile.shape() + "; family " + profile.family());
        if (profile.excludedRecipes().isEmpty()) {
            lines.add("No exact recipe exclusions");
        } else {
            for (ResourceLocation recipeId : profile.excludedRecipes()) {
                lines.add("Excluded recipe " + recipeId);
            }
        }
        return paginate(lines, page);
    }

    public static Optional<Page> provider(boolean creative, List<ProviderPatternStatus> statuses,
            ConfiguredRuleSnapshot rules, Set<ResourceLocation> installedGrants, int page) {
        Objects.requireNonNull(statuses);
        Objects.requireNonNull(rules);
        Objects.requireNonNull(installedGrants);
        var lines = new ArrayList<String>();
        lines.add(creative ? "Creative Provider: ALLOW_ALL_VERIFIED unlock bypass"
                : "Normal Provider: configured Unlock Rules and Installer grants apply");
        if (statuses.isEmpty()) {
            lines.add("No occupied pattern slots or current verified cache");
        }
        for (ProviderPatternStatus status : statuses) {
            var model = status.model();
            var rejection = status.rejection();
            if (model == null) {
                lines.add("Slot " + status.slot() + ": " + status.diagnostic()
                        + (rejection == null ? "" : "; recipe " + rejection.recipeId()
                        + "; trust source adapter " + rejection.adapterId()
                        + "; reason " + rejection.reason()));
                continue;
            }
            lines.add("Slot " + status.slot() + ": " + status.diagnostic()
                    + "; recipe " + model.recipeId() + "; type "
                    + model.recipeTypeId() + "; trust source adapter " + model.adapterId());
            var matchingRules = rules.unlockRules().values().stream()
                    .filter(rule -> ConfiguredUnlockPolicy.matches(rule, model)).toList();
            if (matchingRules.isEmpty()) {
                lines.add("  Normal source: default " + (rules.defaultAllow() ? "ALLOW_ALL" : "DENY_ALL"));
            } else {
                for (var rule : matchingRules) {
                    var missing = rule.requires().stream()
                            .filter(grant -> !installedGrants.contains(grant)).toList();
                    lines.add("  Normal source rule " + rule.id() + (missing.isEmpty()
                            ? " [granted]" : " [missing grants " + missing + "]"));
                }
            }
            lines.add("  Normal " + (ConfiguredUnlockPolicy.allows(rules, model, installedGrants)
                    ? "AVAILABLE" : "LOCKED")
                    + (creative ? "; Creative override AVAILABLE" : ""));
        }
        return paginate(lines, page);
    }

    /** Splits long UTF-8 lines without dropping suffixes, then returns one bounded page. */
    static Optional<Page> paginate(List<String> sourceLines, int page) {
        Objects.requireNonNull(sourceLines);
        var segments = new ArrayList<String>();
        for (String source : sourceLines) {
            Objects.requireNonNull(source);
            int offset = 0;
            do {
                String prefix = offset == 0 ? "" : "... ";
                var part = new StringBuilder(prefix);
                int bytes = utf8Bytes(prefix);
                while (offset < source.length()) {
                    int codePoint = source.codePointAt(offset);
                    String character = new String(Character.toChars(codePoint));
                    int characterBytes = utf8Bytes(character);
                    if (bytes + characterBytes > MAX_LINE_BYTES) {
                        break;
                    }
                    part.append(character);
                    bytes += characterBytes;
                    offset += Character.charCount(codePoint);
                }
                segments.add(part.toString());
            } while (offset < source.length());
        }
        var pages = new ArrayList<List<String>>();
        var content = new ArrayList<String>();
        int contentBytes = PAGE_HEADER_RESERVE_BYTES;
        for (String segment : segments) {
            int bytes = utf8Bytes(segment);
            if (!content.isEmpty() && (content.size() == PAGE_CONTENT_LINES
                    || contentBytes + bytes > MAX_PAGE_BYTES)) {
                pages.add(List.copyOf(content));
                content.clear();
                contentBytes = PAGE_HEADER_RESERVE_BYTES;
            }
            content.add(segment);
            contentBytes += bytes;
        }
        if (!content.isEmpty() || pages.isEmpty()) {
            pages.add(List.copyOf(content));
        }
        int totalPages = pages.size();
        if (page < 1 || page > totalPages) {
            return Optional.empty();
        }
        var lines = new ArrayList<String>();
        lines.add("Diagnostics page " + page + "/" + totalPages);
        lines.addAll(pages.get(page - 1));
        return Optional.of(new Page(page, totalPages, lines));
    }

    private static int utf8Bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    public record Page(int number, int totalPages, List<String> lines) {
        public Page {
            lines = List.copyOf(lines);
            if (number < 1 || totalPages < number || lines.size() > PAGE_CONTENT_LINES + 1) {
                throw new IllegalArgumentException("Invalid diagnostics page");
            }
            int totalBytes = 0;
            for (String line : lines) {
                int lineBytes = utf8Bytes(line);
                if (lineBytes > MAX_LINE_BYTES) {
                    throw new IllegalArgumentException("Diagnostics line exceeds byte budget");
                }
                totalBytes += lineBytes;
            }
            if (totalBytes > MAX_PAGE_BYTES) {
                throw new IllegalArgumentException("Diagnostics page exceeds byte budget");
            }
        }
    }
}
