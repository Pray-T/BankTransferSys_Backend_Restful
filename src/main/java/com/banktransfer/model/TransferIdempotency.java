package com.banktransfer.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 이체 멱등성의 원본. 이체와 같은 트랜잭션에서만 커밋되며, 재조회는 이 행을 따른다.
 * Redis {@link IdempotencyRecord}는 캐시일 뿐 이 엔티티와 역할을 나누지 않는다.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(
        name = "transfer_idempotency",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = TransferIdempotency.UNIQUE_KEY_CONSTRAINT,
                        columnNames = "idempotency_key")
        }
)
public class TransferIdempotency extends BaseTimeEntity {

    public static final String UNIQUE_KEY_CONSTRAINT = "uk_transfer_idempotency_key";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 대소문자를 구분한다. 바이너리 collation이 없으면 {@code AbC}와 {@code abc}가 같은 키가 된다.
     * {@code ddl-auto=update}는 이미 있는 컬럼의 collation을 바꾸지 않는다.
     */
    @Column(
            name = "idempotency_key",
            nullable = false,
            length = Constants.MAX_IDEMPOTENCY_KEY_LENGTH,
            columnDefinition = "VARCHAR(" + Constants.MAX_IDEMPOTENCY_KEY_LENGTH
                    + ") CHARACTER SET ascii COLLATE ascii_bin")
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private TransferIdempotencyStatus status;

    /**
     * 성공 이체. 커밋 전에는 null일 수 있다.
     * 금융 이력은 이 행이 지워져도 남도록 cascade REMOVE / orphanRemoval을 쓰지 않는다.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transfer_id")
    private Transfer transfer;

    public static TransferIdempotency pending(String idempotencyKey, String requestHash) {
        TransferIdempotency row = new TransferIdempotency();
        row.idempotencyKey = idempotencyKey;
        row.requestHash = requestHash;
        row.status = TransferIdempotencyStatus.PENDING;
        return row;
    }

    public void markSuccess(Transfer transfer) {
        if (transfer == null || transfer.getId() == null) {
            throw new IllegalStateException("성공 이체만 멱등성 결과에 연결할 수 있습니다.");
        }
        this.transfer = transfer;
        this.status = TransferIdempotencyStatus.SUCCESS;
    }
}
