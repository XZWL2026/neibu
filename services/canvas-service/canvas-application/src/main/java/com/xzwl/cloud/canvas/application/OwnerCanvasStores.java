package com.xzwl.cloud.canvas.application;

/** A storage view bound to the identity authenticated by IAM, never a client-supplied owner. */
public interface OwnerCanvasStores {
  CanvasStore forOwner(String ownerId);
}
