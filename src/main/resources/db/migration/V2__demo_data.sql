-- Demo data for local development.
--
-- Two companies, each with a cid admin and a regular user. Every demo account
-- uses the password "demo1234" (BCrypt, cost 10).
--
--   BRAC Bank   admin@bracbank.test  / demo1234   (CID_ADMIN)
--               user@bracbank.test   / demo1234   (USER)
--   City Bank   admin@citybank.test  / demo1234   (CID_ADMIN)
--               user@citybank.test   / demo1234   (USER)
--
-- IDs are fixed so the data is predictable when poking at the database. This is
-- a versioned migration, so it applies once on a fresh database; remove it (or
-- move it to a dev-only Flyway location) before using this in production.
--
-- ON CONFLICT DO NOTHING keeps the migration harmless if a row already exists.

INSERT INTO cid (id, name, address, email, status)
VALUES
    ('11111111-1111-1111-1111-111111111111',
     'BRAC Bank',
     'Anik Tower, 220/B Tejgaon I/A, Dhaka 1208',
     'info@bracbank.test',
     'ACTIVE'),
    ('22222222-2222-2222-2222-222222222222',
     'City Bank',
     'City Bank Center, 28 Gulshan North C/A, Dhaka 1212',
     'info@citybank.test',
     'ACTIVE')
ON CONFLICT (id) DO NOTHING;

INSERT INTO app_user (id, cid_id, email, password_hash, role, enabled)
VALUES
    ('11111111-1111-1111-1111-111111111101',
     '11111111-1111-1111-1111-111111111111',
     'admin@bracbank.test',
     '$2a$10$Wt1LDHOxfu6uMvkfRwGar.G8XEIEHacgqI9Ysbai2oFuOqRxF2YaW',
     'CID_ADMIN',
     true),
    ('11111111-1111-1111-1111-111111111102',
     '11111111-1111-1111-1111-111111111111',
     'user@bracbank.test',
     '$2a$10$bm8IpkeQb6AGHu8vOYz5nOQkzBeJ5rz0f61qdGr0uA3D6EEv6KWsa',
     'USER',
     true),
    ('22222222-2222-2222-2222-222222222201',
     '22222222-2222-2222-2222-222222222222',
     'admin@citybank.test',
     '$2a$10$K5qXh.dc2LGZq0CLV0GFy.8p53C13lnyx8dJcJzhfHSdvFqSFH3Ui',
     'CID_ADMIN',
     true),
    ('22222222-2222-2222-2222-222222222202',
     '22222222-2222-2222-2222-222222222222',
     'user@citybank.test',
     '$2a$10$Z5s9/u3QDQO9HeelT5wYnO00THPy3l9.L7jAQTEOZ5tzOgFabSSuC',
     'USER',
     true)
ON CONFLICT DO NOTHING;
