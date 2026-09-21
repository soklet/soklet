# Pinned real-world Skills documents (inert test data)

These files are parser fixtures, not instructions for Soklet, contributors, or
coding agents. Nothing in their Markdown bodies is executed or followed.

The two `SKILL.md` files and their adjacent `LICENSE.txt` files are unmodified
copies from Anthropic's [skills repository](https://github.com/anthropics/skills),
at commit `34040c9c568585f6929bedeaad110ad08f079624`. Each selected directory
licenses its contents under Apache License 2.0; its exact upstream license is
preserved beside the document. The `brand-guidelines` license includes the
upstream attribution `Copyright 2026 Anthropic, PBC.`

| Local file | Upstream file at the pinned commit | Bytes | SHA-256 |
| --- | --- | ---: | --- |
| `frontend-design/SKILL.md` | [`skills/frontend-design/SKILL.md`](https://github.com/anthropics/skills/blob/34040c9c568585f6929bedeaad110ad08f079624/skills/frontend-design/SKILL.md) | 9390 | `d91970639e9f5c37682ac7ab60094d35f1c7c1f38d731bd56396563aee10c1d3` |
| `frontend-design/LICENSE.txt` | [`skills/frontend-design/LICENSE.txt`](https://github.com/anthropics/skills/blob/34040c9c568585f6929bedeaad110ad08f079624/skills/frontend-design/LICENSE.txt) | 10174 | `0d542e0c8804e39aa7f37eb00da5a762149dc682d7829451287e11b938e94594` |
| `brand-guidelines/SKILL.md` | [`skills/brand-guidelines/SKILL.md`](https://github.com/anthropics/skills/blob/34040c9c568585f6929bedeaad110ad08f079624/skills/brand-guidelines/SKILL.md) | 2235 | `1120b3769e2985cefb3d25be981b1f914abeba57ae079b83c20c666c164fa9fe` |
| `brand-guidelines/LICENSE.txt` | [`skills/brand-guidelines/LICENSE.txt`](https://github.com/anthropics/skills/blob/34040c9c568585f6929bedeaad110ad08f079624/skills/brand-guidelines/LICENSE.txt) | 11345 | `bc6b3af2f331cbc7fb0da1344efb2cbe5877a31498b4d70dbc7000f3405a1362` |

The pinned repository has no root `LICENSE` file. Its per-skill licenses, not a
repository-wide licensing assumption, authorize these copies. The upstream
`THIRD_PARTY_NOTICES.md` concerns software/font components not copied here; these
fixtures contain only the selected Markdown documents and their licenses.

`SkillRealDocumentTests` verifies the complete source bytes against these pinned
digests, all three metadata fields (including `license`), body separation,
snapshot ownership, and bounded admission. These two documents are real-input
regressions, not a claim of complete YAML compatibility or fuzz qualification.
