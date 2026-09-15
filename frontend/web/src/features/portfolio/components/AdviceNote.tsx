import styles from './AdviceNote.module.css';

/**
 * The sentence every portfolio page carries: its figures describe the owner's own holdings and are
 * not advice. FinAssist reports on the owner's own data and recommends nothing (requirements,
 * Non-goals; ADR-014).
 */
export function AdviceNote() {
    return (
        <p className={styles.note}>
            These figures describe your own holdings and are not financial advice.
        </p>
    );
}
