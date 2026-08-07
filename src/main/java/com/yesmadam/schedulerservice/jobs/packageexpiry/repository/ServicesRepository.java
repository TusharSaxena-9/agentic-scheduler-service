package com.yesmadam.schedulerservice.jobs.packageexpiry.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.yesmadam.persistence.Services;

@Repository
public interface ServicesRepository extends JpaRepository<Services, Integer> {
  List<Services> findByIdIn(List<Integer> ids);
}
