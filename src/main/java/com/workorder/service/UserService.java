package com.workorder.service;

import com.workorder.entity.User;

public interface UserService {

    User getByUsername(String username);
}
