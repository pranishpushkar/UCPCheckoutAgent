-- I12 (DESIGN 4.3, 10, 19): each intent stores the registry entry it was proposed under (completion, owned, shop_domain,
-- transport as CanonicalJson), written once at insert. Every step and startup compare it with the entry now. A row from
-- before H5 gets the lab entry's value: the only entry it can still run under, since an entry off loopback without
-- owned: true refuses startup. It adds a column and changes no other value.
ALTER TABLE purchase_intent ADD COLUMN merchant_fingerprint TEXT NOT NULL
  DEFAULT '{"completion":"agent","owned":true,"transport":"rest"}';
