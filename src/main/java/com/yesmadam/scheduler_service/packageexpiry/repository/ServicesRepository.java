package com.yesmadam.scheduler_service.packageexpiry.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.yesmadam.scheduler_service.packageexpiry.model.Services;

@Repository
public interface ServicesRepository extends JpaRepository<Services, Integer> {
  List<Services> findByIdIn(List<Integer> ids);
}
