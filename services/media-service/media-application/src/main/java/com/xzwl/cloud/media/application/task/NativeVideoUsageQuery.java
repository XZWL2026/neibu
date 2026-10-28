package com.xzwl.cloud.media.application.task;

import java.time.LocalDate;
import java.util.List;

/** Read model owned by Media; generated tasks belong to one native or enhancement resolution bucket. */
public interface NativeVideoUsageQuery {
  Report query(LocalDate from,LocalDate to);
  record Metric(long tasks,long seconds) {
    public Metric plus(Metric other) {return new Metric(tasks+other.tasks,seconds+other.seconds);}
  }
  record Item(String date,String model,Metric native480,Metric native720,Metric native1080,
      Metric enhanced720,Metric enhanced1080,Metric enhanced4k,Metric total) {}
  record Report(String from,String to,List<Item> items,Metric total) {}
}
