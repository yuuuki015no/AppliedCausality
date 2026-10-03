# Applied Causality changelog

## 0.1.0 — Unreleased

Initial CurseForge release candidate for Minecraft 1.20.1 / Forge and AE2 15.4.10. This entry describes the MOD; it is not Forge's changelog.

### Added

- LGPL-3.0-or-later license, build instructions, and a separate corresponding-source ZIP for GitHub publication before CurseForge distribution.

- Singular Pattern Provider with nine normal AE2 Processing Pattern slots, a survival crafting recipe, and its own block textures.
- Bulk execution of verified deterministic processing recipes through Standard AE2 Crafting CPUs and the supported AdvancedAE Quantum Computer integration.
- Exact checked quantities and persistent ownership of undelivered outputs, including retry, cancellation return, and save/load handling.
- Automatic recipe selection and a Provider menu for choosing validated binding candidates.
- Configured Recipe Type profiles, retained Installer items, and server operator diagnostic commands.
- Creative Singular Pattern Provider for pack testing, with an Unlock bypass that preserves recipe validation.
- Vanilla Smelting support and the configured stock AE2 Inscriber processor-assembly subset.
- Eighteen audited Mekanism recipe subsets. Chemical-key processing requires Applied Mekanistics 1.4.3.

### Initial configuration

- `defaultUnlockPolicy = "DENY_ALL"` and one active Installer slot.
- First launch installs Furnace-gated Smelting, Inscriber-gated processor assembly, and optional Crusher-gated Mekanism Crushing rules.
- Existing rules are preserved; deleted default rules are not regenerated.
- The Inscriber profile explicitly excludes eight plate/print recipes. Four other stock dust recipes reject on empty optional Ingredients; only the three consuming processor-assembly recipes qualify.
- Additional supported recipe subsets need pack-authored Unlock rules.

### Known limits

- Active development: UI, usability, and balance need refinement, and many undiscovered bugs should be expected.
- Recipe interpretation does not simulate machines. Incorrect interpretation, unsafe custom profiles, or implementation bugs can cause item loss or duplication; unfamiliar machine semantics require verified MOD-specific support.
- The default Provider recipe uses only vanilla Minecraft and AE2 materials and is currently too powerful for its acquisition cost. Modpacks are expected to replace the recipe and tune Unlock rules for their end-game progression.

- Future releases may introduce breaking changes; compatibility and migration between versions are not guaranteed.
- Custom `standard_static_item` Recipe Type profiles can make compatible recipes from untested MODs usable, subject to mandatory validation and Unlock rules. Operation and item safety are not guaranteed.
- Crafting CPU Mixins may conflict with other CPU addons or patches, causing crashes or incorrect crafting behavior; arbitrary addon compatibility is not guaranteed.
- Normal AE2 crafting admission and storage limits remain in place; this release adds no long-number request interface.
- Per-recipe machine energy and processing time are outside the 0.1.0 execution model.
- Chance-bearing, retained-input, unknown, and machine-state-dependent recipes are unsupported.
- The configured Inscriber profile needs a fresh audit when a pack adds or removes Inscriber recipes.
- CPU destruction uses AE2 / Applied Mekanistics behavior for unreturned Fluid or Chemical inventory. Allow cancelled-job returns to finish before breaking a CPU.
- Arbitrary late recipe-script mutations are not covered by the current validation evidence.
