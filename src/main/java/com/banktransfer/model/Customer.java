package com.banktransfer.model;

import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
    name = "customers",
    indexes = {
        @Index(name = "idx_customer_name_combined", columnList = "name, bank, gender")
    }
)
public class Customer extends BaseTimeEntity {

    public static final String GENDER_MALE = "MALE";
    public static final String GENDER_FEMALE = "FEMALE";

    public static final String BANK_KB = "KB";
    public static final String BANK_NH = "NH";
    public static final String BANK_WOORI = "WOORI";
    public static final String BANK_HANA = "HANA";
    public static final String BANK_KAKAO = "KAKAO";
    public static final String BANK_TOSS = "TOSS";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Setter(AccessLevel.NONE)
    private Long id;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "bank", nullable = false, length = 100)
    private String bank;

    @Column(name = "age")
    private Integer age;

    @Column(name = "gender", length = 10)
    private String gender;

    @Setter(AccessLevel.NONE)
    @OneToMany(mappedBy = "customer", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Account> accounts = new ArrayList<>();

    public void addAccount(Account account) {
        this.accounts.add(account);
        account.setCustomer(this);
    }
}
