package com.yesmadam.schedulerservice.jobs.cinntrapassbook.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.model.naming.PhysicalNamingStrategyStandardImpl;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import com.yesmadam.schedulerservice.jobs.cinntrapassbook.model.CinntraPassbookAccountCode;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.model.CinntraPassbookCategory;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.model.CinntraPassbookEntry;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.model.CinntraPassbookHub;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.model.CinntraPassbookOrder;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.model.CinntraPassbookSapDocEntry;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.model.CinntraPassbookSapMapping;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.model.CinntraPassbookSapSeries;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.model.CinntraPassbookSapSession;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.model.CinntraPassbookSp;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.model.CinntraPassbookSpAddress;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.model.CinntraPassbookWorkingCategory;

/**
 * Compiles every {@code @Query} on the job's repositories against the real entity metamodel.
 *
 * <p>Nothing else here would catch a bad query before startup: there is no {@code @SpringBootTest},
 * and the service tests mock the repositories. Hibernate translates HQL without a database, so this
 * fails the build on a misspelt property, an entity join it cannot resolve, or a constructor
 * expression whose arguments do not match the row DTO's constructor.
 */
class CinntraPassbookQueryCompileTest {

  private static SessionFactory sessionFactory;

  @BeforeAll
  static void buildMetamodel() {
    Map<String, Object> settings = new HashMap<>();
    settings.put("hibernate.dialect", "org.hibernate.dialect.MySQL8Dialect");
    settings.put("hibernate.temp.use_jdbc_metadata_defaults", false);
    settings.put(
        "hibernate.physical_naming_strategy", PhysicalNamingStrategyStandardImpl.class.getName());
    StandardServiceRegistry registry =
        new StandardServiceRegistryBuilder().applySettings(settings).build();
    MetadataSources sources = new MetadataSources(registry);
    for (Class<?> entity :
        List.of(
            CinntraPassbookEntry.class,
            CinntraPassbookSp.class,
            CinntraPassbookHub.class,
            CinntraPassbookSapMapping.class,
            CinntraPassbookSapSeries.class,
            CinntraPassbookWorkingCategory.class,
            CinntraPassbookAccountCode.class,
            CinntraPassbookOrder.class,
            CinntraPassbookCategory.class,
            CinntraPassbookSpAddress.class,
            CinntraPassbookSapDocEntry.class,
            CinntraPassbookSapSession.class)) {
      sources.addAnnotatedClass(entity);
    }
    sessionFactory = sources.buildMetadata().buildSessionFactory();
  }

  @AfterAll
  static void close() {
    sessionFactory.close();
  }

  @Test
  void everyRepositoryQueryCompiles() {
    List<String> checked = new ArrayList<>();
    List<String> errors = new ArrayList<>();
    try (Session session = sessionFactory.openSession()) {
      for (Class<?> repository :
          List.of(
              CinntraPassbookReadRepository.class,
              CinntraPassbookWriteRepository.class,
              CinntraPassbookSapSessionRepository.class)) {
        for (Method method : repository.getDeclaredMethods()) {
          Query query = method.getAnnotation(Query.class);
          if (query == null) {
            continue;
          }
          checked.add(method.getName());
          try {
            session.createQuery(query.value());
          } catch (RuntimeException e) {
            errors.add(repository.getSimpleName() + "#" + method.getName() + ": " + e.getMessage());
          }
        }
      }
    }
    assertThat(errors).isEmpty();
    // Guard against a vacuous pass if the annotations ever stop being found.
    assertThat(checked).hasSize(16);
  }
}
