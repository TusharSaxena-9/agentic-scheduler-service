package com.yesmadam.schedulerservice.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guards the one bug class that neither the compiler nor a mocked repository test can catch: a
 * native query whose column type does not match the type its projection interface declares.
 *
 * <p>It cost a failed UAT run to learn. {@code tbl_order.booking_date} is a {@code DATETIME}, so
 * the driver returns a {@code java.sql.Timestamp}; the projections declare {@code java.sql.Date};
 * and Spring Data's proxy refuses to narrow one to the other, throwing {@code
 * UnsupportedOperationException: Cannot project java.sql.Timestamp to java.sql.Date} on the first
 * row returned. Three separate projections had it.
 *
 * <p>Why a source-text assertion rather than a proper test: proving the binding needs a real
 * database, and there is no {@code @SpringBootTest} or Testcontainer here. A mocked repository
 * hands back whatever type the test constructs, so it agrees with the projection by definition and
 * can never fail. Scanning the query text is crude, but it fails the build if someone removes the
 * CAST — which is exactly the regression that already happened once.
 *
 * <p>Delete this in favour of a real integration test the moment one exists.
 */
class ProjectionTypeAgreementTest {

  /** Selects {@code booking_date} under the {@code bookingDate} alias, in any spacing. */
  private static final Pattern BOOKING_DATE_SELECT =
      Pattern.compile("(?i)([\\w.()\\s]{0,40}booking_date[\\w.()\\s]{0,40}?)AS\\s+bookingDate");

  private static final Path JOBS = Paths.get("src/main/java/com/yesmadam/schedulerservice/jobs");

  /**
   * Job packages whose {@code bookingDate} projection declares {@code java.sql.Date}.
   *
   * <p>Scoped by declared type, not by filename, because the requirement is type-specific: {@code
   * java.sql.Date} cannot be narrowed from a {@code Timestamp}, but {@code LocalDate} <em>can</em>
   * — Spring Data's conversion service handles that one. So {@code sebaltagging}, which declares
   * {@code LocalDate}, correctly needs no CAST and must not be flagged.
   */
  private static List<Path> jobsRequiringCast() throws IOException {
    try (Stream<Path> paths = Files.walk(JOBS)) {
      return paths
          .filter(p -> p.getFileName().toString().endsWith("Projection.java"))
          .filter(ProjectionTypeAgreementTest::declaresSqlDateBookingDate)
          .map(p -> p.getParent().getParent())
          .distinct()
          .collect(Collectors.toList());
    }
  }

  private static boolean declaresSqlDateBookingDate(Path projection) {
    try {
      String src = new String(Files.readAllBytes(projection), StandardCharsets.UTF_8);
      return src.contains("import java.sql.Date;") && src.contains("Date getBookingDate();");
    } catch (IOException e) {
      throw new IllegalStateException("cannot read " + projection, e);
    }
  }

  private static List<Path> repositoryFiles() throws IOException {
    List<Path> files = new java.util.ArrayList<>();
    for (Path jobDir : jobsRequiringCast()) {
      try (Stream<Path> paths = Files.walk(jobDir)) {
        paths
            .filter(p -> p.getFileName().toString().endsWith("Repository.java"))
            .forEach(files::add);
      }
    }
    return files;
  }

  @Test
  @DisplayName("every query aliasing booking_date casts it to DATE")
  void bookingDateIsAlwaysCast() throws IOException {
    for (Path file : repositoryFiles()) {
      String source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
      Matcher matcher = BOOKING_DATE_SELECT.matcher(source);
      while (matcher.find()) {
        String selectExpression = matcher.group(1);
        assertThat(selectExpression.toLowerCase())
            .withFailMessage(
                "%s selects booking_date as bookingDate without a CAST:%n  %s%n"
                    + "booking_date is a DATETIME and the projection declares java.sql.Date, so this"
                    + " throws \"Cannot project java.sql.Timestamp to java.sql.Date\" on the first"
                    + " row. Use cast(booking_date as date).",
                file.getFileName(), selectExpression.trim())
            .contains("cast");
      }
    }
  }

  @Test
  @DisplayName("a Boolean projection getter is only used over an explicitly cast column")
  void booleanGettersAreCastOrTinyint1() throws IOException {
    // The trap is that tinyint(1) works and tinyint does not, and nothing in the Java says which
    // you have. Requiring a CAST for any Boolean-typed getter removes the dependency on a column's
    // display width entirely — a column someone may widen without ever looking at this code.
    Pattern booleanGetter = Pattern.compile("Boolean\\s+get(\\w+)\\(\\);");
    try (Stream<Path> paths = Files.walk(JOBS)) {
      for (Path projection :
          paths
              .filter(p -> p.getFileName().toString().endsWith("Projection.java"))
              .collect(Collectors.toList())) {
        String src = new String(Files.readAllBytes(projection), StandardCharsets.UTF_8);
        Matcher m = booleanGetter.matcher(src);
        while (m.find()) {
          String alias = Character.toLowerCase(m.group(1).charAt(0)) + m.group(1).substring(1);
          for (Path repo : repositoriesNear(projection)) {
            String sql = new String(Files.readAllBytes(repo), StandardCharsets.UTF_8);
            Matcher sel =
                Pattern.compile("(?i)([\\w.()\\s]{0,60}?)AS\\s+" + Pattern.quote(alias) + "\\b")
                    .matcher(sql);
            while (sel.find()) {
              assertThat(sel.group(1).toLowerCase())
                  .withFailMessage(
                      "%s selects '%s' into a Boolean getter without a CAST:%n  %s%n"
                          + "A tinyint(1) column maps to Boolean but a plain tinyint maps to Byte,"
                          + " which throws at runtime. Cast it, or widen the getter to Number.",
                      repo.getFileName(), alias, sel.group(1).trim())
                  .contains("cast");
            }
          }
        }
      }
    }
  }

  private static List<Path> repositoriesNear(Path projection) throws IOException {
    try (Stream<Path> paths = Files.walk(projection.getParent().getParent())) {
      return paths
          .filter(p -> p.getFileName().toString().endsWith("Repository.java"))
          .collect(Collectors.toList());
    }
  }

  @Test
  @DisplayName("the scan actually finds the queries it is meant to guard")
  void guardIsNotVacuous() throws IOException {
    // Without this, renaming the alias would silently make the test above pass on zero matches.
    int matches = 0;
    for (Path file : repositoryFiles()) {
      Matcher matcher =
          BOOKING_DATE_SELECT.matcher(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
      while (matcher.find()) {
        matches++;
      }
    }
    assertThat(matches)
        .withFailMessage(
            "Expected to find booking_date projections to guard but found %d. Either the alias was"
                + " renamed or the queries moved — update this test rather than deleting it.",
            matches)
        .isGreaterThanOrEqualTo(5);
  }
}
