package com.yesmadam.schedulerservice.jobs.userwalletsum.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.yesmadam.schedulerservice.jobs.userwalletsum.model.DailyCustomerBalance;

@Repository
public interface DailyCustomerBalanceRepository
    extends JpaRepository<DailyCustomerBalance, Integer> {}
