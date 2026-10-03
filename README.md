# Applied Causality

Applied Causality is an end-game **Applied Energistics 2 addon for Minecraft 1.20.1 / Forge**. A Singular Pattern Provider turns supported, verified processing recipes into bulk ME crafting transactions: provide the ingredients for many operations and receive their exact combined outputs without running a row of physical machines.

Version 0.1.0 is being prepared for its first release. The source repository and versioned release source will be published on GitHub before the MOD is distributed through CurseForge. Downloads and player support will be on the official Applied Causality CurseForge project page. No GitHub checkout is needed to install or play.

## Development status

**Applied Causality is still under active development.** The UI and usability need substantial refinement, and many undiscovered bugs should be expected. Test the MOD in a separate world and back up important saves before using it in a world you care about.

**Future releases may introduce breaking changes** to configuration formats, recipe support, gameplay behavior, or saved data. Compatibility and migration between versions are not guaranteed. Read the release notes and back up your world and configuration before updating.

## Recipe interpretation and item safety

Bulk processing reads recipe definitions to determine material inputs and outputs. It does **not simulate or operate the original machines**. A MOD's actual machine behavior can differ from what its recipe data exposes, including retained inputs, additional outputs, or quantities affected by machine state.

Recipe validation is mandatory, and recognized unsupported or uncertain semantics are rejected. However, validation cannot automatically establish machine behavior that the recipe data does not describe. Incorrect interpretation, unsafe custom profiles, or implementation bugs can cause **item loss or duplication**. The audited subsets and tests reduce that risk; they do not establish safety for arbitrary MOD combinations or recipe changes.

When a machine's material behavior cannot be established through generic recipe interpretation, support requires a **MOD-specific recipe interpreter (adapter)** and verification against that behavior. Adding an Unlock rule alone does not provide that support. Dedicated interpretation for a tested subset of Mekanism machine recipes is already implemented as part of validating this approach; coverage is limited to the audited subsets listed below.

## Installation

Install the Forge editions of the following mods on **both the client and server**:

| Component | Required version / baseline |
| --- | --- |
| Minecraft | 1.20.1 |
| Java | 17 |
| Forge | 47.4.10 development baseline |
| Applied Energistics 2 | 15.4.10 |
| GuideME | 20.1.7, required by AE2 15.4.10 |
| Applied Causality | 0.1.0 |

Put the downloaded release JARs in the instance's `mods/` directory and start Minecraft or the dedicated server. If you use the CurseForge app, also check that AE2's own required dependencies were installed. Use the versions above for the documented baseline; other versions have not all been verified.

Optional integrations:

| Mod | Audited version | Purpose |
| --- | --- | --- |
| Mekanism | 1.20.1-10.4.16.80 | Supported Mekanism recipe subsets |
| Applied Mekanistics | 1.4.3 | Required alongside Mekanism for Chemical processing patterns and storage |
| AdvancedAE | 1.3.5-1.20.1 | Quantum Computer crafting integration; install its own required dependencies, including GeckoLib |
| JEI | 15.56.0.205 | Recipe lookup and item discovery |

These integrations are optional. Basic AE2 and Vanilla Smelting use does not require them.

## Craft the Provider

The normal **Singular Pattern Provider** uses this shaped recipe:

```text
S P S
C N C
S P S
```

- `S`: AE2 Singularity (four total)
- `P`: AE2 Pattern Provider (two total)
- `C`: AE2 Crafting Unit (two total)
- `N`: Nether Star (one total)

The recipe produces one Provider. Obtaining an AE2 Singularity unlocks its recipe-book entry. The Creative Singular Pattern Provider has no survival recipe; it is available in the Functional Blocks creative tab.

## Balance and modpack progression

The default Provider recipe uses materials obtainable with **vanilla Minecraft and AE2 alone**, without another content MOD. At present, its bulk-processing power is disproportionate to that default acquisition cost. The intended use is for **modpacks to replace the crafting recipe and adjust Unlock rules to place the Provider at their own end-game progression stage**. The standard recipe and current balance still need refinement.

## First craft: iron smelting

1. Connect a normal Singular Pattern Provider to a powered ME network with an available channel, Crafting CPU, and stored raw iron.
2. Encode a normal AE2 **Processing Pattern** with exactly **one Raw Iron → one Iron Ingot**. Use the ingredients and outputs for one operation.
3. Right-click the Provider to open its menu. Shift-click the pattern from your inventory to insert it; the Provider has nine pattern slots. A normal left-click on a pattern slot selects it for viewing its validation and binding candidates.
4. Put a **Furnace item** in the first Installer slot. The default Smelting rule requires it. The Furnace stays in the slot and is not consumed or operated as a machine.
5. When the pattern is available, request several Iron Ingots through an ME crafting terminal. The Provider performs the verified processing in bulk.

The initial configuration uses `DENY_ALL` and **one active Installer slot**. Without a matching rule and its Installer, a verified recipe stays locked. Four Installer slots are visible, but only the configured number grants permission. Server owners can set `installerSlots = 2..4` and restart to enable more.

The other initial examples are:

| Processing Pattern | Installer |
| --- | --- |
| 1 Printed Logic Processor + 1 Redstone + 1 Printed Silicon → 1 Logic Processor | AE2 Inscriber |
| 1 Cobblestone → 1 Gravel (with Mekanism installed) | Mekanism Crusher |

The stock Calculation and Engineering Processor assembly recipes also qualify. Printed circuits, printed silicon, plate copying, and dust-producing Inscriber recipes do not qualify. The included Inscriber profile is audited for stock AE2 15.4.10; pack authors must review added or removed Inscriber recipes before using it.

## Binding and configuration

A uniquely matching verified recipe is selected automatically. If several recipes match, select the pattern slot and choose a **Validated binding candidate**. **Clear binding** restores automatic selection. Binding chooses a recipe; it does not bypass validation or Installer requirements. Editing a pattern or changing recipes causes its binding to be checked again.

On first launch the server creates:

```text
config/appliedcausality-common.toml
config/appliedcausality/rules/smelting.toml
config/appliedcausality/rules/ae2-inscriber.toml
config/appliedcausality/rules/mekanism-crushing.toml
```

Existing files are preserved. Each default rule is generated once; deleting it keeps it disabled on subsequent launches. The Crushing rule is ignored when Mekanism is absent. Rule changes are read at startup and after a successful server `/reload`; restart after common-config edits.

See the [configuration guide](docs/CONFIG_RULES.md) for rule formats, copyable templates, and operator diagnostics. It is included in the release JAR along with the templates under `examples/config/`; the JAR can be opened as a ZIP archive to read them.

You can try recipes from **MODs that have not been tested with Applied Causality** by adding their Recipe Type under `[[recipe_types]]` in a rule file, using the existing `standard_static_item` interpretation, and configuring any required Unlock rules. This can make compatible recipes usable without a dedicated MOD adapter. It is limited to fixed Item inputs and outputs that satisfy that interpretation's contract; it does not add arbitrary machine, Fluid, or Chemical support. **Operation and item safety are not guaranteed for these custom profiles.** Mandatory validation still applies, and rejected recipes remain unavailable. Audit the actual machine behavior and test in a separate world; see the configuration guide for the exact contract.

## Supported recipes and limits

Vanilla Smelting and the configured stock AE2 processor-assembly subset work out of the box when their Installers are present. The Mekanism adapter implements 18 exact recipe subsets, but only Crushing has an initial Unlock rule. Pack authors must add suitable rules to enable the others.

- Item processing: Enriching, Crushing, Smelting, Combining, and Sawing with no secondary output and zero secondary chance.
- With Applied Mekanistics 1.4.3: Oxidizing, Gas Conversion, Infusion Conversion, Pigment Extracting, Metallurgic Infusing, Centrifuging, Crystallizing, Chemical Infusing, Pigment Mixing, Separating, Washing, Pressurized Reaction, and either direction of Rotary processing.

Support applies to the audited fixed material quantities, recipe classes, and ingredient forms within those types. Chance results, retained catalysts, unknown recipe semantics, and machine-state-dependent processing are rejected. Configuring an Unlock rule does not add support for an unsupported recipe.

Normal ME power, channels, crafting planning, and Crafting Storage limits still apply. Version 0.1.0 does not model each physical recipe's energy cost or processing time, and it does not provide a new interface for arbitrarily large crafting requests. Unknown or changed recipes are revalidated; arbitrary recipe-script mutations after loading have not been audited.

Undelivered bulk outputs are saved and retried. After cancellation, allow the Crafting CPU to return its inventory before breaking it: standard AE2 may discard unreturned Fluids on CPU destruction, and Applied Mekanistics uses its own Chemical destruction behavior. Keep Applied Mekanistics installed while saved Chemical crafting jobs or returns exist.

## Crafting CPU addon compatibility

Applied Causality uses **Mixins in crafting CPU logic** to integrate bulk execution with AE2 and the audited AdvancedAE implementation. Other addons that add or modify crafting CPUs, or patch the same logic, may cause **Mixin conflicts, crashes, or incorrect crafting behavior**. Compatibility with arbitrary CPU addons is not guaranteed. Test your exact MOD combination before using it in an important world; the listed integration versions describe only the audited baseline.

## Troubleshooting and support

- **Locked:** check the matching rule, Installer item, and whether its slot is active.
- **No process model / invalid:** check that the recipe is supported and the pattern has the exact per-operation inputs and outputs.
- **Ambiguous process model:** select a validated binding candidate in the Provider menu.
- **Waiting after crafting or cancellation:** check storage space and allow output returns to finish.

Report problems in the comments on the Applied Causality CurseForge project page. Include the mod versions, recipe ID and pattern amounts, steps to reproduce, and relevant log lines. Server operators can include `/appliedcausality diagnostics provider <x> <y> <z>` output. Remove private addresses or credentials from logs before posting them.

## Development transparency

Applied Causality is developed with extensive use of AI-assisted coding.

## License and documentation

**Copyright (c) 2026 yuuki015.** Original Applied Causality code, assets, documentation, and configuration examples are licensed under **LGPL-3.0-or-later** (GNU Lesser General Public License, version 3 or any later version). Use, modification, redistribution, modpack/server-pack inclusion, and commercial use are permitted under its terms. Redistribution must preserve notices and meet the applicable source-provision requirements; modifications to covered code remain under the applicable LGPL/GPL terms. See [LICENSE.txt](LICENSE.txt) and [NOTICE.txt](NOTICE.txt). Separately identified third-party material and dependencies retain their own licenses.

Corresponding source for each release will be available on GitHub through the **Source link on the CurseForge project page and the version-specific source link in the file description**. Use the matching release tag or source ZIP, including source, resources, Gradle wrapper/build files, and [BUILDING.md](BUILDING.md), to build or modify that release. The playable JAR does not include a source archive. Source availability is a prerequisite for publishing the binary.

[CHANGELOG.md](CHANGELOG.md) records Applied Causality changes. The [documentation index](docs/README.md) links to configuration examples, build instructions, and license notices.
