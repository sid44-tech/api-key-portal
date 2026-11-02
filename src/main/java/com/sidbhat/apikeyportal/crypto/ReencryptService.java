package com.sidbhat.apikeyportal.crypto;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sidbhat.apikeyportal.logging.AuditLog;

/**
 * Moves every encrypted value onto the active encryption key.
 * <p>
 * Works in raw SQL rather than through the entities on purpose. Reading an
 * entity would decrypt the value through the JPA converter and writing it would
 * re-encrypt it - which happens to be the right operation, but it would also
 * touch {@code updated_at}, fire audit listeners, and make every key in the
 * portal look edited on the same morning. The ciphertext is the only thing that
 * should change here, so the ciphertext is the only thing this touches.
 * <p>
 * Safe to run repeatedly and safe to interrupt: each value is independent, the
 * work is skipped for anything already on the active key, and a row is only
 * written after its new ciphertext has been produced.
 */
@Service
public class ReencryptService {

    private static final Logger log = LoggerFactory.getLogger(ReencryptService.class);

    /** The encrypted columns, as {@code table -> column}. */
    static final List<EncryptedColumn> COLUMNS = List.of(
            new EncryptedColumn("api_key", "key_value"),
            new EncryptedColumn("service_integration", "client_secret"),
            new EncryptedColumn("sso_configuration", "client_secret"));

    private final JdbcTemplate jdbc;
    private final EncryptionService encryption;
    private final AuditLog audit;

    public ReencryptService(JdbcTemplate jdbc, EncryptionService encryption, AuditLog audit) {
        this.jdbc = jdbc;
        this.encryption = encryption;
        this.audit = audit;
    }

    /** One table's encrypted column. */
    record EncryptedColumn(String table, String column) {
    }

    /**
     * @param examined values looked at
     * @param rewritten values moved onto the active key
     * @param failed values that could not be decrypted, and were left alone
     */
    public record Result(int examined, int rewritten, int failed) {

        public boolean isComplete() {
            return failed == 0;
        }
    }

    /** How many stored values are not yet on the active key, by key id. */
    @Transactional(readOnly = true)
    public Map<String, Integer> countByKeyId() {
        java.util.Map<String, Integer> counts = new java.util.TreeMap<>();
        for (EncryptedColumn column : COLUMNS) {
            for (String value : readAll(column)) {
                counts.merge(EncryptionService.keyIdOf(value), 1, Integer::sum);
            }
        }
        return counts;
    }

    /**
     * Re-encrypts everything not already on the active key.
     * <p>
     * One transaction for the lot: a half-rotated database is not wrong - every
     * value still says which key opens it - but an all-or-nothing run is far
     * easier to reason about when something fails in the middle, and the volume
     * here is a few thousand short strings at most.
     *
     * @param actor who asked, for the audit trail
     */
    @Transactional
    public Result reencryptAll(String actor) {
        log.info("Re-encrypting all stored values onto key '{}'", encryption.getActiveKeyId());
        int examined = 0;
        int rewritten = 0;
        int failed = 0;

        for (EncryptedColumn column : COLUMNS) {
            for (Map<String, Object> row : jdbc.queryForList(
                    "select id, " + column.column() + " as value from " + column.table())) {
                String value = (String) row.get("value");
                if (value == null) {
                    continue;
                }
                examined++;
                if (encryption.isOnActiveKey(value)) {
                    continue;
                }
                Long id = ((Number) row.get("id")).longValue();
                try {
                    String rewrittenValue = encryption.encrypt(encryption.decrypt(value));
                    jdbc.update("update " + column.table() + " set " + column.column() + " = ? where id = ?",
                            rewrittenValue, id);
                    rewritten++;
                } catch (EncryptionException e) {
                    // the key that wrote this one is not configured, or is wrong.
                    // Say which row, so it can be chased - never what is in it.
                    log.error("Could not re-encrypt {}.{} id {} (written with key '{}'): {}",
                            column.table(), column.column(), id, EncryptionService.keyIdOf(value),
                            e.getMessage());
                    failed++;
                }
            }
        }

        Result result = new Result(examined, rewritten, failed);
        if (failed > 0) {
            // the transaction is about to roll back; say so plainly
            audit.refused("encryption_key_rotated", actor, "all stored values",
                    failed + " value(s) could not be decrypted; nothing was changed");
            throw new EncryptionException("Re-encryption stopped: " + failed + " of " + examined
                    + " value(s) could not be decrypted with the configured keys. Nothing was changed. "
                    + "Configure the key they were written with and run this again.");
        }
        audit.allowed("encryption_key_rotated", actor, "all stored values",
                "Re-encrypted " + rewritten + " of " + examined + " value(s) onto key '"
                        + encryption.getActiveKeyId() + "'");
        log.info("Re-encrypted {} of {} value(s) onto key '{}'", rewritten, examined,
                encryption.getActiveKeyId());
        return result;
    }

    private List<String> readAll(EncryptedColumn column) {
        return jdbc.queryForList("select " + column.column() + " from " + column.table(), String.class);
    }
}
