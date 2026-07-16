package com.ideas.contracts.service;

interface NotificationSink {
  String name();

  void deliver(NotificationEvent event);
}
