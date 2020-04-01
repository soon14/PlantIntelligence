/*
 *    Copyright (c) 2018-2025, lengleng All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 * Redistributions in binary form must reproduce the above copyright
 * notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 * Neither the name of the pig4cloud.com developer nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 * Author: lengleng (wangiegie@gmail.com)
 */
package com.pig4cloud.pigx.admin.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.pig4cloud.pigx.admin.api.entity.*;
import com.pig4cloud.pigx.admin.mapper.SysTenantMapper;
import com.pig4cloud.pigx.admin.service.*;
import com.pig4cloud.pigx.common.core.constant.CacheConstants;
import com.pig4cloud.pigx.common.core.constant.CommonConstants;
import com.pig4cloud.pigx.common.core.constant.enums.DictTypeEnum;
import com.pig4cloud.pigx.common.core.exception.CheckedException;
import com.pig4cloud.pigx.common.data.tenant.TenantContextHolder;
import lombok.AllArgsConstructor;
import org.apache.commons.configuration.Configuration;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.PropertiesConfiguration;
import org.springframework.beans.BeanUtils;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 租户
 *
 * @author lengleng
 * @date 2019-05-15 15:55:41
 */
@Service
@AllArgsConstructor
public class SysTenantServiceImpl extends ServiceImpl<SysTenantMapper, SysTenant> implements SysTenantService {
	private static final PasswordEncoder ENCODER = new BCryptPasswordEncoder();
	private final SysOauthClientDetailsService clientServices;
	private final SysDeptRelationService deptRelationService;
	private final SysUserRoleService userRoleService;
	private final SysRoleMenuService roleMenuService;
	private final SysDictItemService dictItemService;
	private final SysUserService userService;
	private final SysRoleService roleService;
	private final SysMenuService menuService;
	private final SysDeptService deptService;
	private final SysDictService dictService;

	/**
	 * 获取正常状态租户
	 * <p>
	 * 1. 状态正常
	 * 2. 开始时间小于等于当前时间
	 * 3. 结束时间大于等于当前时间
	 *
	 * @return
	 */
	@Override
	@Cacheable(value = CacheConstants.TENANT_DETAILS)
	public List<SysTenant> getNormalTenant() {
		return baseMapper.selectList(Wrappers.<SysTenant>lambdaQuery()
				.eq(SysTenant::getStatus, CommonConstants.STATUS_NORMAL));
	}

	/**
	 * 保存租户
	 * <p>
	 * 1. 保存租户
	 * 2. 初始化权限相关表
	 * - sys_user
	 * - sys_role
	 * - sys_menu
	 * - sys_user_role
	 * - sys_role_menu
	 * - sys_dict
	 * - sys_dict_item
	 * - sys_client_details
	 *
	 * @param sysTenant 租户实体
	 * @return
	 */
	@Override
	@Transactional(rollbackFor = Exception.class)
	@CacheEvict(value = CacheConstants.TENANT_DETAILS)
	public Boolean saveTenant(SysTenant sysTenant) {
		this.save(sysTenant);

		// 查询系统内置字典
		List<SysDict> dictList = new ArrayList<>(dictService.list());
		// 查询系统内置字典项目
		List<Integer> dictIdList = dictList.stream().map(SysDict::getId)
				.collect(Collectors.toList());
		List<SysDictItem> dictItemList = new ArrayList<>(dictItemService
				.list(Wrappers.<SysDictItem>lambdaQuery()
						.in(SysDictItem::getDictId, dictIdList)));
		// 查询当前租户菜单
		List<SysMenu> menuList = menuService.list();
		// 查询客户端配置
		List<SysOauthClientDetails> clientDetailsList = clientServices.list();
		// 保证插入租户为新的租户
		TenantContextHolder.setTenantId(sysTenant.getId());
		Configuration config = getConfig();

		// 插入部门
		SysDept dept = new SysDept();
		dept.setName(config.getString("defaultDeptName"));
		dept.setParentId(0);
		deptService.save(dept);
		//维护部门关系
		deptRelationService.insertDeptRelation(dept);
		// 构造初始化用户
		SysUser user = new SysUser();
		user.setUsername(config.getString("defaultUsername"));
		user.setPassword(ENCODER.encode(config.getString("defaultPassword")));
		user.setDeptId(dept.getDeptId());
		userService.save(user);
		// 构造新角色
		SysRole role = new SysRole();
		role.setRoleCode(config.getString("defaultRoleCode"));
		role.setRoleName(config.getString("defaultRoleName"));
		roleService.save(role);
		// 插入新的菜单
		menuService.saveBatch(menuList);
		// 用户角色关系
		SysUserRole userRole = new SysUserRole();
		userRole.setUserId(user.getUserId());
		userRole.setRoleId(role.getRoleId());
		userRoleService.save(userRole);
		// 查询全部菜单,构造角色菜单关系
		List<SysRoleMenu> collect = menuList.stream().map(menu -> {
			SysRoleMenu roleMenu = new SysRoleMenu();
			roleMenu.setRoleId(role.getRoleId());
			roleMenu.setMenuId(menu.getMenuId());
			return roleMenu;
		}).collect(Collectors.toList());
		roleMenuService.saveBatch(collect);
		// 插入系统字典
		dictService.saveBatch(dictList);
		// 处理字典项最新关联的字典ID
		List<SysDictItem> itemList = dictList.stream()
				.flatMap(dict -> dictItemList.stream()
						.filter(item -> item.getType().equals(dict.getType()))
						.peek(item -> item.setDictId(dict.getId())))
				.collect(Collectors.toList());

		//插入客户端
		clientServices.saveBatch(clientDetailsList);
		return dictItemService.saveBatch(itemList);
	}

	/**
	 * 获取配置信息
	 */
	private Configuration getConfig() {
		try {
			return new PropertiesConfiguration("tenant/tenant.properties");
		} catch (ConfigurationException e) {
			throw new CheckedException("获取配置文件失败，", e);
		}
	}
}
