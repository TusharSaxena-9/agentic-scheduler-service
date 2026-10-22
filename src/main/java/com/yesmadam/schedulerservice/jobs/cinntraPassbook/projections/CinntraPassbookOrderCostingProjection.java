package com.yesmadam.schedulerservice.jobs.cinntraPassbook.projections;

/** An order that exists, with its category's SAP costing code ({@code null} when the category has none). */
public interface CinntraPassbookOrderCostingProjection {

  Long getOrderId();

  String getCostingCode();
}
