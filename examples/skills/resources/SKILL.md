---
name: toy-catalog-guide
description: A synthetic guide for exploring a tiny toy catalog.
example:
  catalog:
    currency: USD
    departments:
      - puzzles
      - outdoor
  provenance:
    kind: synthetic
    revision: 1
---
# Toy catalog guide

Use this Skill to answer simple questions about the bundled synthetic catalog.
The example inventory is stored in `references/catalog.csv`; no entry represents
a real product, price, customer, or business.

When comparing items, report the SKU, name, category, and price from the CSV.
Treat `assets/sample.bin` as an opaque demonstration of a binary supporting file.
