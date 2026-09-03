package com.yesmadam.schedulerservice.jobs.spwalletsum.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.yesmadam.schedulerservice.jobs.spwalletsum.model.DailySpBalance;

@Repository
public interface DailySpBalanceRepository extends JpaRepository<DailySpBalance, Integer> {}
