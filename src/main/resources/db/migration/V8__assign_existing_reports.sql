-- Reports filed before routing existed have no professional. Assign each to the client's
-- open-conversation professional for that service, else the least-loaded accepting one.
UPDATE reports r
SET assigned_professional_id = COALESCE(
    (SELECT c.professional_id FROM conversations c
     WHERE c.client_id = r.client_id AND c.service_type_id = r.service_type_id AND c.status = 'OPEN'
     LIMIT 1),
    (SELECT p.user_id
     FROM professionals p
     JOIN service_types st ON st.required_professional_type = p.professional_type
     JOIN users u ON u.id = p.user_id
     WHERE st.service_type_id = r.service_type_id AND p.is_accepting_clients
       AND u.status NOT IN ('SUSPENDED', 'DEACTIVATED')
     ORDER BY (SELECT COUNT(*) FROM conversations c2
               WHERE c2.professional_id = p.user_id AND c2.status = 'OPEN'), p.user_id
     LIMIT 1))
WHERE r.assigned_professional_id IS NULL;
