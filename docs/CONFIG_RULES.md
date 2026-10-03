# Applied Causality configuration and Installer rules

Place UTF-8 TOML files directly in `config/appliedcausality/rules/`. The server reads them at startup and after a successful server data reload. A missing directory means no configured rules. One invalid file rejects the whole candidate; an invalid initial load stops server startup.

On first launch, Forge creates `config/appliedcausality-common.toml` with `defaultUnlockPolicy = "DENY_ALL"` and `installerSlots = 1`. Applied Causality creates active rules in the same config directory:

```text
config/
├ appliedcausality-common.toml
└ appliedcausality/
   └ rules/
      ├ smelting.toml
      ├ ae2-inscriber.toml
      └ mekanism-crushing.toml  (applied only when Mekanism is installed)
```

Existing config files are not overwritten. Each rule is generated only once; deleting a generated rule keeps it disabled on later launches. With `DENY_ALL`, verified processes without a matching satisfied rule stay locked. The Mekanism file has `requires_mod = "mekanism"`, so it remains safely installed when Mekanism is absent and becomes active when Mekanism is installed.
Custom rules for other optional mods can use the same `requires_mod` key with their Mod ID.

## Default Smelting rule

The repository keeps the initial values at [`examples/config/appliedcausality-common.toml`](../examples/config/appliedcausality-common.toml) and [`examples/config/appliedcausality/rules/smelting.toml`](../examples/config/appliedcausality/rules/smelting.toml). For Gradle `runClient`, the active files are under `run/config/`. Copy a source rule back only if you want to restore one that you removed. Do not place duplicate copies of a rule under different filenames: duplicate IDs reject the rule load.

1. Start a world or server and make a normal AE2 Processing Pattern for one `minecraft:raw_iron` → one `minecraft:iron_ingot`. Place it in a normal Singular Pattern Provider connected to an AE2 network. The built-in Vanilla Smelting adapter handles this Recipe Type; no `recipe_types` profile is needed.
2. With the Provider's Installer slot empty, the verified Smelting pattern is locked. Run `/appliedcausality diagnostics rules` and `/appliedcausality diagnostics provider <x> <y> <z>` as an operator to see the loaded rule and missing grant.
3. Put one `minecraft:furnace` in the Provider's first Installer slot. The grant unlocks the pattern; removing the Furnace locks it again. The initial common config sets `installerSlots = 1`, so only the first slot grants permission.
4. Change `installerSlots` to `4` and restart the server to check activation of all retained slots. The Creative Provider bypasses only this Unlock decision; it still validates the recipe and Pattern normally.
5. To check the default policy, remove `smelting.toml` while keeping `defaultUnlockPolicy = "DENY_ALL"`, then restart. The verified Smelting pattern stays locked even with a Furnace installed because no rule matches it. Temporarily set `defaultUnlockPolicy = "ALLOW_ALL"` and restart to see the unruled pattern become available; restore `"DENY_ALL"` and copy the source rule back to test Installer gating again.

To finish the test, restore the desired common-config values and restart the server. A successful `/reload` also reloads rule files, but a restart makes the common-config state explicit. These defaults do not change recipe validation or Bulk safety rules.

## Default AE2 Inscriber example

The active [`ae2-inscriber.toml`](../examples/config/appliedcausality/rules/ae2-inscriber.toml) demonstrates a configured Recipe Type profile without a dedicated Java Adapter. Its `standard_static_item` profile explicitly excludes eight bundled AE2 15.4.10 `inscribe` recipes for plate copying and printed circuits/silicon. The four dust-producing `inscribe` recipes are not listed as exclusions; the configured resolver rejects their empty top/bottom Ingredients. Only the three bundled `press` processor-assembly recipes qualify: they consume all three input items and have fixed Item outputs. The Inscriber BlockItem (`ae2:inscriber`) is the retained Installer; no extra `requires_mod` is needed because AE2 is a required dependency.

Make an AE2 Processing Pattern for one `ae2:printed_logic_processor`, one `minecraft:redstone`, and one `ae2:printed_silicon` producing one `ae2:logic_processor`. Put it in the normal Singular Pattern Provider. It should remain locked until one `ae2:inscriber` is placed in an active Installer slot. The same profile also permits the stock Calculation and Engineering Processor assembly patterns. It does not accelerate the physical Inscriber or consume its energy; Bulk uses only the verified recipe quantities.

This is a **stock AE2 15.4.10 example**, not a blanket guarantee for every pack. The configured resolver does not inspect AE2's `press`/`inscribe` mode. If a Data Pack or addon adds `ae2:inscriber` recipes, the pack author must audit every added recipe and exclude any that retain inputs, use special output state, or otherwise violate `standard_static_item`. Remove this profile until that audit is complete. An excluded recipe ID must exist in the active RecipeManager; removing a stock recipe from the pack requires updating this file too.

## Default Mekanism Crushing rule

`mekanism-crushing.toml` is generated on first launch. With Mekanism 1.20.1-10.4.16.80 installed it becomes an active rule; without Mekanism it is ignored. Its [source template](../examples/config/appliedcausality/rules/mekanism-crushing.toml) can restore it after deletion. This Item-to-Item rule does not require Applied Mekanistics.

1. Make a normal AE2 Processing Pattern for one `minecraft:cobblestone` → one `minecraft:gravel` and put it in a normal Singular Pattern Provider. Mekanism's bundled `mekanism:crushing/cobblestone_to_gravel` recipe supplies this exact test case on the audited version. No configured `recipe_types` profile is needed because the Mekanism adapter already supports `mekanism:crushing`.
2. With no Crusher in an active Installer slot, the verified Crushing pattern is locked. Put one `mekanism:crusher` in an active slot to unlock it; remove the Crusher to lock it again. Check `/appliedcausality diagnostics provider <x> <y> <z>` for the selected Mekanism recipe and missing/present grant. A `0 configured Recipe Types` count in `diagnostics rules` is expected for these built-in adapters.
3. To use both default rules on one Provider, swap Furnace and Crusher in the first slot, or set `installerSlots = 2` and restart to use both grants at once. Removing this rule, or renaming it with a non-`.toml` extension, disables it on the next start (or after a successful `/reload`); it is not regenerated. Renaming it to another valid `.toml` filename keeps it active.

## Trying recipes from untested MODs

A rule file can add a MOD's Recipe Type under `[[recipe_types]]` with `semantics = "standard_static_item"`, even when Applied Causality has no dedicated adapter or compatibility test for that MOD. Compatible recipes can then become usable after mandatory Core validation and any applicable Unlock requirements. An Unlock rule alone does not add a Recipe Type profile.

**Operation and item safety are not guaranteed for untested custom profiles.** This interpretation is limited to fixed Item inputs and outputs under the contract below; it does not infer arbitrary machine behavior or add Fluid/Chemical handling. Audit every included recipe against the actual machine's material behavior and exclude incompatible recipes. Test in a separate world with backups; configuration cannot bypass a validation rejection.

## Rule format

```toml
version = 1

[[grants]]
id = "applied_causality_example:smelting_access"

[[installers]]
id = "applied_causality_example:furnace_installer"
item = "minecraft:furnace"
grants = ["applied_causality_example:smelting_access"]

[[unlock_rules]]
id = "applied_causality_example:smelting_requires_furnace"
recipe_type = "minecraft:smelting"
requires = ["applied_causality_example:smelting_access"]
```

Every file requires `version = 1`. The optional top-level `requires_mod` names one lowercase Mod ID. When that Mod is absent, the entire file is skipped after its top-level keys and schema version are checked. When it is present, all rule references are validated normally. The only top-level tables are `recipe_types`, `grants`, `installers`, and `unlock_rules`; all are arrays of tables. The permitted keys are:

| Table | Keys |
| --- | --- |
| `recipe_types` | `id`, `recipe_type`, `semantics`, `process_shape`, `process_family`, optional `machine_profile`, optional `exclude_recipes` |
| `grants` | `id` |
| `installers` | `id`, exactly one of `item` or `item_tag`, optional `grants` |
| `unlock_rules` | `id`, optional `recipe_id`, `recipe_type`, `source_mod`, `process_shape`, `process_family`, `machine_profile`, `requires` |

`semantics` currently accepts only `standard_static_item`. Every ID is an explicit lowercase `namespace:path`. `source_mod` is a lowercase mod namespace. An Unlock Rule needs at least one matcher. Grant references must be declared in some file. Recipe Type, Installer item, exact excluded recipe, and exact Unlock Rule recipe IDs must exist on the server. Each excluded recipe must belong to its profile's Recipe Type; an Unlock Rule specifying both recipe and type must match them. Repeated rule IDs or profiles for one Recipe Type reject the whole candidate. Unknown keys at any level reject it too.

Add a `recipe_types` profile only for a Recipe Type whose material semantics you have audited. The smoke test uses the already bundled Vanilla Smelting adapter, so adding a separate Smelting profile would create a second candidate with potentially different semantics.

For `standard_static_item`, the pack author must verify that every included recipe consumes one item per Ingredient entry and has exactly the fixed `getResultItem` output, with no remaining item, catalyst, chance result, hidden byproduct, or special item/machine state. The resolver checks observable Ingredients, concrete Pattern items, and the declared result. It cannot safely call a recipe's container-specific `assemble` or `getRemainingItems` with a generic container; the author must verify any behavior those methods add and exclude recipes that violate the contract.

`config/appliedcausality-common.toml` provides `defaultUnlockPolicy = "ALLOW_ALL"` or `"DENY_ALL"` and `installerSlots = 1..4` (default 1). A verified process matching no Unlock Rule uses the default policy. When several rules match, it must satisfy every matching rule. A rule with `requires = []` explicitly allows its match under `DENY_ALL`.

The four physical Installer slots retain one stack each. Only the configured number of active slots grant permissions. Disabling a slot or removing its Installer definition leaves the stored stack extractable; its grant is revoked. An Installer can name an exact Item or BlockItem using `item`, or an item tag using `item_tag`. Unknown or currently empty tags grant nothing. Installer slots are operated through the Provider menu; automation remains limited to the original Processing Pattern inventory. Rule and common-config reloads rebuild availability and AE2 advertisement on the server. Binding a recipe does not bypass Unlock.

## Creative Provider and author diagnostics

`applied_causality:creative_singular_pattern_provider` is in the Creative Functional Blocks tab and is available with `/give`. It has no Survival recipe. It uses the same Pattern slots, resolver, Core validation, and Bulk execution as the normal Provider. Only the post-validation Unlock decision changes: every verified process is available without Installer grants. Configured Recipe Type eligibility, exact exclusions, and all recipe safety checks still apply.

Operators (permission level 2) can inspect the active server state with:

```text
/appliedcausality diagnostics rules [page]
/appliedcausality diagnostics type <recipe_type> [page]
/appliedcausality diagnostics provider <x> <y> <z> [page]
```

`rules` shows the active generation, configured Types and exclusions counts, missing or empty Installer tags, and the last rejected reload diagnostics. `type` lists the selected profile and exact recipe exclusions. `provider` shows each occupied Pattern slot's validation outcome, verified recipe and adapter trust source, matching normal Unlock Rules and missing grants, plus any Creative override. For an unbound configured recipe rejected after all supported Ingredients match the Pattern inputs, `provider` shows its Recipe ID, adapter ID, and concrete reason (for example `RECIPE_OUTPUT_NBT` or `RECIPE_OUTPUT_EMPTY`). A known output Item must also match the Pattern's output Item before an output-state refusal is attributed. Earlier refusals such as `UNSUPPORTED_INGREDIENT` are shown when the recipe is explicitly bound; unbound discovery cannot reliably attribute them to a Pattern. Core validation refusals show a `CORE_` diagnostic reason. Each command starts at page 1; pass a positive page number to read later entries. Every output line is limited to 512 UTF-8 bytes and each page to 4096 UTF-8 bytes; long entries continue on lines prefixed `... `. Configuration reload failures are also logged by the server. A failed initial load stops startup and reports its offending references in the startup error.
