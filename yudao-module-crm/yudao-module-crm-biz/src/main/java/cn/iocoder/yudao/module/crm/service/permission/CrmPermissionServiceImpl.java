package cn.iocoder.yudao.module.crm.service.permission;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjUtil;
import cn.iocoder.yudao.module.crm.controller.admin.permission.vo.CrmPermissionUpdateReqVO;
import cn.iocoder.yudao.module.crm.convert.permission.CrmPermissionConvert;
import cn.iocoder.yudao.module.crm.dal.dataobject.permission.CrmPermissionDO;
import cn.iocoder.yudao.module.crm.dal.mysql.permission.CrmPermissionMapper;
import cn.iocoder.yudao.module.crm.framework.enums.CrmBizTypeEnum;
import cn.iocoder.yudao.module.crm.framework.enums.CrmPermissionLevelEnum;
import cn.iocoder.yudao.module.crm.service.permission.bo.CrmPermissionCreateReqBO;
import cn.iocoder.yudao.module.crm.service.permission.bo.CrmPermissionTransferReqBO;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import javax.annotation.Resource;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertSet;
import static cn.iocoder.yudao.module.crm.enums.ErrorCodeConstants.*;
import static cn.iocoder.yudao.module.crm.framework.enums.CrmPermissionLevelEnum.isOwner;

/**
 * CRM 数据权限 Service 接口实现类
 *
 * @author HUIHUI
 */
@Service
@Validated
public class CrmPermissionServiceImpl implements CrmPermissionService {

    @Resource
    private CrmPermissionMapper crmPermissionMapper;

    @Resource
    private AdminUserApi adminUserApi;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createPermission(CrmPermissionCreateReqBO createBO) {
        // 1. 校验用户是否存在
        adminUserApi.validateUserList(Collections.singletonList(createBO.getUserId()));

        // 2. 创建
        CrmPermissionDO permission = CrmPermissionConvert.INSTANCE.convert(createBO);
        crmPermissionMapper.insert(permission);
        return permission.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updatePermission(CrmPermissionUpdateReqVO updateReqVO) {
        // 1. 校验存在
        validateCrmPermissionExists(updateReqVO.getIds());
        // 2. 更新
        List<CrmPermissionDO> updateDO = CrmPermissionConvert.INSTANCE.convertList(updateReqVO);
        crmPermissionMapper.updateBatch(updateDO);
    }

    private void validateCrmPermissionExists(Collection<Long> ids) {
        List<CrmPermissionDO> permissionList = crmPermissionMapper.selectBatchIds(ids);
        if (ObjUtil.notEqual(permissionList.size(), ids.size())) {
            throw exception(CRM_PERMISSION_NOT_EXISTS);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void transferPermission(CrmPermissionTransferReqBO transferReqBO) {
        // 1. 校验数据权限：是否是负责人，只有负责人才可以转移
        CrmPermissionDO oldPermission = crmPermissionMapper.selectByBizTypeAndBizIdByUserId(
                transferReqBO.getBizType(), transferReqBO.getBizId(), transferReqBO.getUserId());
        String bizTypeName = CrmBizTypeEnum.getNameByType(transferReqBO.getBizType());
        // TODO 校验是否为超级管理员 || 1
        if (oldPermission == null || !isOwner(oldPermission.getLevel())) {
            throw exception(CRM_PERMISSION_DENIED, bizTypeName);
        }
        // 1.1 校验转移对象是否已经是该负责人
        if (ObjUtil.equal(transferReqBO.getNewOwnerUserId(), oldPermission.getUserId())) {
            throw exception(CRM_PERMISSION_MODEL_TRANSFER_FAIL_OWNER_USER_EXISTS, bizTypeName);
        }
        // 1.2 校验新负责人是否存在
        adminUserApi.validateUserList(Collections.singletonList(transferReqBO.getNewOwnerUserId()));

        // 2. 修改新负责人的权限
        List<CrmPermissionDO> permissions = crmPermissionMapper.selectByBizTypeAndBizId(
                transferReqBO.getBizType(), transferReqBO.getBizId()); // 获得所有数据权限
        CrmPermissionDO permission = CollUtil.findOne(permissions,
                item -> ObjUtil.equal(item.getUserId(), transferReqBO.getNewOwnerUserId()));
        if (permission == null) {
            crmPermissionMapper.insert(new CrmPermissionDO().setBizType(transferReqBO.getBizType())
                    .setBizId(transferReqBO.getBizId()).setUserId(transferReqBO.getNewOwnerUserId())
                    .setLevel(CrmPermissionLevelEnum.OWNER.getLevel()));
        } else {
            crmPermissionMapper.updateById(new CrmPermissionDO().setId(permission.getId())
                    .setLevel(CrmPermissionLevelEnum.OWNER.getLevel()));
        }

        // 3. 修改老负责人的权限
        if (transferReqBO.getOldOwnerPermissionLevel() != null) {
            crmPermissionMapper.updateById(new CrmPermissionDO().setId(oldPermission.getId())
                    .setLevel(transferReqBO.getOldOwnerPermissionLevel()));
        } else {
            crmPermissionMapper.deleteById(oldPermission.getId());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deletePermission(Collection<Long> ids) {
        // 校验存在
        validateCrmPermissionExists(ids);

        // 删除
        crmPermissionMapper.deleteBatchIds(ids);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deletePermission(Integer bizType, Long bizId, Integer level) {
        List<CrmPermissionDO> permissions = crmPermissionMapper.selectListByBizTypeAndBizIdAndLevel(
                bizType, bizId, level);
        // 校验存在
        if (CollUtil.isEmpty(permissions)) {
            throw exception(CRM_PERMISSION_NOT_EXISTS);
        }

        // 删除数据权限
        crmPermissionMapper.deleteBatchIds(convertSet(permissions, CrmPermissionDO::getId));
    }

    @Override
    public CrmPermissionDO getPermission(Long id, Long userId) {
        return crmPermissionMapper.selectByIdAndUserId(id, userId);
    }

    @Override
    public List<CrmPermissionDO> getPermissionListByBiz(Integer bizType, Long bizId) {
        return crmPermissionMapper.selectByBizTypeAndBizId(bizType, bizId);
    }

    @Override
    public List<CrmPermissionDO> getPermissionList(Collection<Long> ids) {
        if (CollUtil.isEmpty(ids)) {
            return Collections.emptyList();
        }
        return crmPermissionMapper.selectBatchIds(ids);
    }

    @Override
    public List<CrmPermissionDO> getPermissionListByBizTypeAndUserId(Integer bizType, Long userId) {
        return crmPermissionMapper.selectListByBizTypeAndUserId(bizType, userId);
    }

}