-- ADR 0021 (fatia 3): a equipe lembra o tutor pelo WhatsApp (link wa.me, sem envio automático) e o agendamento guarda QUANDO, para ninguém
-- lembrar duas vezes. Opcional: nulo = ainda não lembrado. Não é um evento do histórico (pode ser repetido de propósito, ex.: 2 dias antes e de manhã).
ALTER TABLE appointments ADD COLUMN reminded_at timestamptz;
