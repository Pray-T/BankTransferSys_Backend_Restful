package com.banktransfer.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.banktransfer.model.Customer;

public interface CustomerRepository extends JpaRepository<Customer, Long> {

    List<Customer> findByNameAndBank(String name, String bank); 

    boolean existsByNameAndBank(String name, String bank);
}

