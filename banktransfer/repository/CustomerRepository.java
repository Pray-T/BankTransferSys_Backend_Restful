package com.banktransfer.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.banktransfer.model.Customer;

public interface CustomerRepository extends JpaRepository<Customer, Long> {
}

