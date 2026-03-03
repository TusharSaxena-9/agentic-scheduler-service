package com.yesmadam.scheduler_service.packageexpiry.model.enums;

public enum PackageSessionEnum {
  ACTIVE(1),
  CANCELLED(2),
  EXPIRED(3),
  COMPLETED(4);

  private final int status;

  PackageSessionEnum(int status) {
    this.status = status;
  }

  public int getStatus() {
    return status;
  }
}
