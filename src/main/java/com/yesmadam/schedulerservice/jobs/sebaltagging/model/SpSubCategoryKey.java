package com.yesmadam.schedulerservice.jobs.sebaltagging.model;

/** Composite (SP, sub-category) map key — a proper value type instead of a string-concat hack. */
public record SpSubCategoryKey(Integer spId, Integer subCategoryId) {}
