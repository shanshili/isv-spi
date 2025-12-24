package com.isv.spi.models;

import java.io.Serializable;
import java.util.Date;

public class UserInfo implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private String orderBizId;
    private String aliUid;
    private String productCode;
    private String packageVersion;
    private String orderId;
    private String instanceId;
    private Date createTime;
    private Date expiredOn;
    private boolean trial;
    private String status; // ACTIVE, EXPIRED, RELEASED
    private boolean virtualUser = false; // 标记是否为虚拟用户
    
    // 固定返回的特定密码
    private static final String FIXED_PASSWORD = "tskyide";
    
    public UserInfo() {
        this.createTime = new Date();
        this.status = "ACTIVE";
    }
    
    // Getters and Setters
    public String getOrderBizId() { return orderBizId; }
    public void setOrderBizId(String orderBizId) { this.orderBizId = orderBizId; }
    
    public String getAliUid() { return aliUid; }
    public void setAliUid(String aliUid) { this.aliUid = aliUid; }
    
    public String getProductCode() { return productCode; }
    public void setProductCode(String productCode) { this.productCode = productCode; }
    
    public String getPackageVersion() { return packageVersion; }
    public void setPackageVersion(String packageVersion) { this.packageVersion = packageVersion; }
    
    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
    
    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }
    
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    
    public Date getExpiredOn() { return expiredOn; }
    public void setExpiredOn(Date expiredOn) { this.expiredOn = expiredOn; }
    
    public boolean isTrial() { return trial; }
    public void setTrial(boolean trial) { this.trial = trial; }
    
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    
    // 获取固定密码
    public String getFixedPassword() {
        return FIXED_PASSWORD;
    }
    
    // 检查是否过期
    public boolean isExpired() {
        if (expiredOn == null) {
            return false;
        }
        return new Date().after(expiredOn);
    }
    
    // 检查是否有效
    public boolean isValid() {
        return "ACTIVE".equals(status) && !isExpired();
    }
    
    @Override
    public String toString() {
        return "UserInfo{" +
                "orderBizId='" + orderBizId + '\'' +
                ", aliUid='" + aliUid + '\'' +
                ", productCode='" + productCode + '\'' +
                ", instanceId='" + instanceId + '\'' +
                ", expiredOn=" + expiredOn +
                ", isExpired=" + isExpired() +
                ", status='" + status + '\'' +
                ", createTime=" + createTime +
                '}';
    }
    private String computeNestInstanceId;

    public String getComputeNestInstanceId() {
        return computeNestInstanceId;
    }

    public void setComputeNestInstanceId(String computeNestInstanceId) {
        this.computeNestInstanceId = computeNestInstanceId;
    }
    
    public boolean isVirtualUser() {
        return virtualUser;
    }

    public void setVirtualUser(boolean virtualUser) {
        this.virtualUser = virtualUser;
    }
    
}