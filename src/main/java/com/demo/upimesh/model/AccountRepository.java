package com.demo.upimesh.model;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRepository extends JpaRepository<Account, String> { //This gives you lots of ready-made methods without writing any code. eg-save(account);
} //Account specifies the entity the repository manages, while Long specifies the data type of its primary key (id).
