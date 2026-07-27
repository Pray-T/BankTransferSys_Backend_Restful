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
    private Long id;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "bank", nullable = false, length = 100)
    private String bank;

    @Column(name = "age")
    private Integer age;

    @Column(name = "gender", length = 10)
    private String gender;

    @OneToMany(mappedBy = "customer", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Account> accounts = new ArrayList<>(); 

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getBank() {
        return bank;
    }

    public void setBank(String bank) {
        this.bank = bank;
    }

    public Integer getAge() {
        return age;
    }

    public void setAge(Integer age) {
        this.age = age;
    }

    public String getGender() {
        return gender;
    }

    public void setGender(String gender) {
        this.gender = gender;
    }

    public List<Account> getAccounts() { 
        return accounts; 
    }

    public void addAccount(Account account) {
        this.accounts.add(account);
        account.setCustomer(this); // 반대편(Account)에도 나(Customer)를 설정
    }
}
