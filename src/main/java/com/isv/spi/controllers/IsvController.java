package com.isv.spi.controllers;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.isv.spi.models.UserInfo;
import com.isv.spi.services.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;

import jakarta.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.Date;
import java.util.HashSet;
import java.util.List;

@Controller
@RequestMapping(value="/isv")
public class IsvController {

    private static final Logger logger = LoggerFactory.getLogger(IsvController.class);

    @Autowired
    private HttpServletRequest request;
    
    @Autowired
    private StorageService storageService;

    // 云市场分配的秘钥
    private static final String SECRET_KEY = "**";

    // 固定的特定密码（用于返回）
    private static final String FIXED_PASSWORD = "tskyide";
    
    // 已知的系统参数
    private static final HashSet<String> SYSTEM_PARAMS = new HashSet<>(Arrays.asList(
            "productCode", "package_version", "orderId", "orderBizId", "action",
            "aliUid", "expiredOn", "skuId", "trial", "token"
    ));

    // 缓存上次的状态，避免频繁重载
    private final Map<String, Boolean> statusCache = new ConcurrentHashMap<>();

    /**
     * default action
     * @return
     */
    @RequestMapping(value="")
    @ResponseBody
    public String defaultAction() {
        JSONObject result = new JSONObject();
        result.put("error", "wrong action");
        return result.toJSONString();
    }

    private void logRequestDetails(String action) {
        logger.info("=== Incoming Request ===");
        logger.info("Action: {}", action);
        logger.info("Timestamp: {}", new Date());
    
        Map<String, String[]> params = request.getParameterMap();
        for(Map.Entry<String, String[]> entry : params.entrySet()) {
            logger.info("{}: {}", entry.getKey(), Arrays.toString(entry.getValue()));
        }
        logger.info("========================");
    }
    
    private void triggerNginxReload() {
        try {
            // 使用ProcessBuilder执行脚本
            ProcessBuilder pb = new ProcessBuilder("/usr/local/bin/nginx-reload-signal");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            // 异步读取输出
            new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        logger.debug("[Nginx Reload Signal] {}", line);
                    }
                } catch (IOException e) {
                    logger.warn("读取信号输出失败: {}", e.getMessage());
                }
            }).start();
            
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                logger.info("Nginx重载信号已发送。");
            } else {
                logger.warn("发送重载信号失败，退出码: {}", exitCode);
            }
        } catch (IOException | InterruptedException e) {
            logger.error("发送Nginx重载信号失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 生成实例状态标记文件
     */
    private void createInstanceStatusFile(String instanceId, boolean isValid) {
        String statusDir = "/etc/nginx/conf.d/instance_status/";
        String statusFile = statusDir + instanceId + ".conf";
        String newContent = instanceId + " " + (isValid ? "true" : "false") + ";";
        
        // 检查状态是否变化
        Boolean oldStatus = statusCache.get(instanceId);
        boolean statusChanged = (oldStatus == null) || (oldStatus != isValid);
        
        try {
            File dir = new File(statusDir);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            
            // 如果状态变化，更新文件和缓存
            if (statusChanged) {
                try (FileWriter writer = new FileWriter(statusFile)) {
                    writer.write(newContent);
                }
                // 确保文件权限
                Files.setPosixFilePermissions(Paths.get(statusFile), 
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));
                
                // 更新缓存
                statusCache.put(instanceId, isValid);
                logger.info("状态变化，更新文件: {} = {}", instanceId, isValid);
                
                // 智能延迟：避免短时间内多次重载
                scheduleDelayedReload(instanceId);
            } else {
                logger.debug("状态未变化，跳过更新: {}", instanceId);
            }
            
        } catch (Exception e) {
            logger.error("处理状态文件失败: {}", e.getMessage(), e);
        }
    }

    // 延迟重载调度器
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

    private void scheduleDelayedReload(String instanceId) {
        // 取消该实例的已有调度任务
        ScheduledFuture<?> existingTask = scheduledTasks.get(instanceId);
        if (existingTask != null) {
            existingTask.cancel(false);
        }
        
        // 延迟5秒执行重载（合并短时间内多次变化）
        ScheduledFuture<?> newTask = scheduler.schedule(() -> {
            try {
                logger.info("执行延迟重载: {}", instanceId);
                triggerNginxReload();
            } finally {
                scheduledTasks.remove(instanceId);
            }
        }, 5, TimeUnit.SECONDS);
        
        scheduledTasks.put(instanceId, newTask);
    }

    // 应用关闭时清理资源
    @PreDestroy
    public void cleanup() {
        scheduler.shutdown();
    }

    /**
     * 删除实例状态标记文件
     */
    private void deleteInstanceStatusFile(String instanceId) {
        try {
            String statusDir = "/etc/nginx/conf.d/instance_status/";
            String statusFile = statusDir + instanceId + ".conf";
            File file = new File(statusFile);
            if (file.exists()) {
                file.delete();
                logger.info("删除实例状态文件: {}", statusFile);
            }

                // 修复：从缓存中移除对应记录
            statusCache.remove(instanceId);
            logger.info("从缓存中移除实例状态: {}", instanceId);
            
            // 如果存在定时任务，取消它
            ScheduledFuture<?> scheduledTask = scheduledTasks.get(instanceId);
            if (scheduledTask != null) {
                scheduledTask.cancel(false);
                scheduledTasks.remove(instanceId);
                logger.info("取消实例的延迟重载任务: {}", instanceId);
            }
        } catch (Exception e) {
            logger.error("删除实例状态文件失败: {}", e.getMessage(), e);
        }
    }
    /**
     * 新增：检查实例是否过期的接口
     * POST /isv/check
     * Content-Type: application/x-www-form-urlencoded 或 application/json
     * 参数: aliuid, instanceid, apikey
     * 返回: true 或 false
     */
    @RequestMapping(value="/check", method = RequestMethod.POST)
    @ResponseBody
    public String checkInstanceExpiry() {
        // 记录请求
        logRequestDetails("checkInstance");
        
        // 获取参数（支持表单和JSON格式）
        String aliUid = request.getParameter("aliuid");
        String computeNestInstanceId = request.getParameter("instanceid");
        
        // 如果没有表单参数，尝试从请求体读取JSON
        if ((aliUid == null || computeNestInstanceId == null) && 
            request.getContentType() != null && 
            request.getContentType().contains("application/json")) {
            try {
                StringBuilder requestBody = new StringBuilder();
                String line;
                while ((line = request.getReader().readLine()) != null) {
                    requestBody.append(line);
                }
                
                if (requestBody.length() > 0) {
                    JSONObject json = JSONObject.parseObject(requestBody.toString());
                    if (aliUid == null) aliUid = json.getString("aliuid");
                    if (computeNestInstanceId == null) computeNestInstanceId = json.getString("instanceid");
                }
            } catch (Exception e) {
                logger.error("解析JSON请求体失败: {}", e.getMessage());
            }
        }
        
        // 参数校验
        if (aliUid == null || aliUid.trim().isEmpty()) {
            logger.error("缺少参数: aliuid");
            createInstanceStatusFile(computeNestInstanceId, false);
            return "false";
        }

        if (computeNestInstanceId == null || computeNestInstanceId.trim().isEmpty()) {
            logger.error("缺少参数: instanceid");
            return "false";
        }
        
        boolean hasValidInstance = false;
        UserInfo activeUser = null;
        
        // 方案1: 首先尝试通过aliUid查找用户
        List<UserInfo> userInfoList = storageService.getUsersByAliUid(aliUid);
        if (userInfoList != null && !userInfoList.isEmpty()) {
            // 找到第一个有效的用户实例
            for (UserInfo user : userInfoList) {
                if (user.isValid()) {
                    activeUser = user;
                    hasValidInstance = true;
                    break;
                }
            }
            
            if (activeUser != null) {
                // 更新computeNestInstanceId关联（如果不同）
                if (!computeNestInstanceId.equals(activeUser.getComputeNestInstanceId())) {
                    storageService.updateComputeNestInstanceId(activeUser.getOrderBizId(), computeNestInstanceId);
                    logger.info("通过aliUid找到有效用户，关联计算巢实例ID: {} -> {}", 
                        computeNestInstanceId, activeUser.getOrderBizId());
                }
            }
        }
        
        // 方案2: 如果通过aliUid没找到，尝试通过computeNestInstanceId查找
        if (!hasValidInstance) {
            activeUser = storageService.getUserByComputeNestInstanceId(computeNestInstanceId);
            if (activeUser != null && activeUser.isValid()) {
                hasValidInstance = true;
                
                // 如果是虚拟用户，更新aliUid为传入的真实aliUid
                if (storageService.isVirtualUser(activeUser)) {
                    storageService.updateUserAliUid(activeUser.getOrderBizId(), aliUid);
                    logger.info("虚拟用户更新aliUid: {} -> {} (实例ID: {})", 
                        activeUser.getAliUid(), aliUid, computeNestInstanceId);
                }
                
                logger.info("通过computeNestInstanceId找到有效用户: {}", computeNestInstanceId);
            }
        }
        
        logger.info("检查用户实例状态: aliUid={}, 计算巢实例ID={}, 是否有有效实例={}", 
            aliUid, computeNestInstanceId, hasValidInstance);
        
        // 生成状态标记文件
        createInstanceStatusFile(computeNestInstanceId, hasValidInstance);

        // 返回true或false
        return hasValidInstance ? "true" : "false";
    }


    /**
     * 创建实例
     * @return
     */
    @RequestMapping(value="", params="action=createInstance")
    @ResponseBody
    public String createInstance() {
        // 记录传入请求
        logRequestDetails("createInstance");
    
        // 校验token
        if(!validateToken()) {
            JSONObject result = new JSONObject();
            result.put("error", "token is invalid");
            logger.warn("Token验证失败");
            return result.toJSONString();
        }

        // 获取必需参数
        String orderBizId = request.getParameter("orderBizId");
        String aliUid = request.getParameter("aliUid");
        
        // 参数校验
        if (orderBizId == null || orderBizId.trim().isEmpty()) {
            JSONObject result = new JSONObject();
            result.put("error", "orderBizId is required");
            logger.warn("缺少必要参数: orderBizId");
            return result.toJSONString();
        }
        
        if (aliUid == null || aliUid.trim().isEmpty()) {
            JSONObject result = new JSONObject();
            result.put("error", "aliUid is required");
            logger.warn("缺少必要参数: aliUid");
            return result.toJSONString();
        }

            // 修复：检查并清理可能存在的旧缓存
        String instanceId = orderBizId.trim();
        statusCache.remove(instanceId);
        logger.info("创建实例前清理缓存: {}", instanceId);

        // 检查是否已存在相同订单
        UserInfo existingUser = storageService.getUserByOrderBizId(orderBizId);
        if (existingUser != null) {
            logger.info("订单已存在，返回现有信息: " + orderBizId);
            JSONObject result = new JSONObject();
            result.put("instanceId", existingUser.getInstanceId());
            result.put("aliUid", existingUser.getAliUid());
            result.put("password", FIXED_PASSWORD);
            logger.info("Returning existing instance: " + result.toJSONString());
            return result.toJSONString();
        }

        // 创建用户信息对象
        UserInfo userInfo = new UserInfo();
        userInfo.setOrderBizId(orderBizId.trim());
        userInfo.setAliUid(aliUid.trim());
        
        // 设置其他可选参数
        userInfo.setProductCode(getParameter("productCode"));
        userInfo.setPackageVersion(getParameter("package_version"));
        userInfo.setOrderId(getParameter("orderId"));
        userInfo.setInstanceId(orderBizId.trim()); // 使用orderBizId作为instanceId
        
        // 设置过期时间
        String expiredOnStr = request.getParameter("expiredOn");
        if (expiredOnStr != null && !expiredOnStr.isEmpty()) {
            try {
                // 尝试解析为时间戳（长整型）
                long timestamp = Long.parseLong(expiredOnStr);
                userInfo.setExpiredOn(new Date(timestamp));
                logger.info("解析为时间戳: " + timestamp);
            } catch (NumberFormatException e1) {
                // 如果不是时间戳，尝试解析为日期时间字符串
                try {
                    // 根据阿里云可能的日期格式进行解析
                    // 例如：yyyy-MM-dd HH:mm:ss
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                    Date expiredDate = sdf.parse(expiredOnStr);
                    userInfo.setExpiredOn(expiredDate);
                } catch (Exception e2) {
                    logger.error("过期时间格式错误，无法解析: " + expiredOnStr);
                }
            }
        }
        
        // 是否试用
        String trialParam = request.getParameter("trial");
        userInfo.setTrial("true".equalsIgnoreCase(trialParam));
        userInfo.setStatus("ACTIVE");

        // 保存用户信息
        try {
            storageService.saveUser(userInfo);
            logger.info("用户信息保存成功: {}", userInfo);
        } catch (Exception e) {
            logger.error("保存用户信息失败", e);
            JSONObject result = new JSONObject();
            result.put("error", "保存用户信息失败: " + e.getMessage());
            return result.toJSONString();
        }

        // 记录验证成功的请求
        logger.info("Valid createInstance request received at: {}", new Date());
        // 返回结果 - 只返回instanceId、aliUid和固定密码
        JSONObject result = new JSONObject();
        result.put("instanceId", orderBizId.trim());
        result.put("aliUid", aliUid.trim());
        result.put("password", FIXED_PASSWORD);
        
        logger.info("Returning: {}", result.toJSONString());
        return result.toJSONString();
    }

    /**
     * 续费实例
     * @return
     */
    @RequestMapping(value="", params="action=renewInstance")
    @ResponseBody
    public String renewInstance() {
        // 记录传入请求
        logRequestDetails("renewInstance");

        // 校验token
        if(!validateToken()) {
            JSONObject result = new JSONObject();
            result.put("error", "token is invalid");
            return result.toJSONString();
        }

        // 获取必需参数
        String instanceId = request.getParameter("instanceId");
        String orderId = request.getParameter("orderId");
        String expiredOnStr = request.getParameter("expiredOn");
        
        // 参数校验 - 按照文档要求，instanceId、orderId、expiredOn是必选参数
        if (instanceId == null || instanceId.trim().isEmpty()) {
            JSONObject result = new JSONObject();
            result.put("error", "instanceId is required");
            return result.toJSONString();
        }
        
        if (orderId == null || orderId.trim().isEmpty()) {
            JSONObject result = new JSONObject();
            result.put("error", "orderId is required");
            return result.toJSONString();
        }
        
        if (expiredOnStr == null || expiredOnStr.trim().isEmpty()) {
            JSONObject result = new JSONObject();
            result.put("error", "expiredOn is required");
            return result.toJSONString();
        }

        UserInfo userInfo = storageService.getUserByInstanceId(instanceId);
        if (userInfo == null) {
            JSONObject result = new JSONObject();
            result.put("error", "实例不存在: " + instanceId);
            return result.toJSONString();
        }

        // 更新过期时间
        try {
            // 按照文档要求的格式解析过期时间：yyyy-MM-dd HH:mm:ss
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            Date expiredDate = sdf.parse(expiredOnStr.trim());
            userInfo.setExpiredOn(expiredDate);
            userInfo.setStatus("ACTIVE");
            
            // 如果有ecsInstanceId参数（计算巢实例ID），更新关联
            String ecsInstanceId = request.getParameter("ecsInstanceId");
            if (ecsInstanceId != null && !ecsInstanceId.trim().isEmpty()) {
                userInfo.setComputeNestInstanceId(ecsInstanceId.trim());
                logger.info("更新计算巢实例ID关联: {} -> {}", instanceId, ecsInstanceId);
            }
            
            storageService.saveUser(userInfo);
            logger.info("实例续费成功: {}", instanceId);
            
            // 创建有效状态标记文件
            String computeNestInstanceId = userInfo.getComputeNestInstanceId();
            if (computeNestInstanceId != null && !computeNestInstanceId.trim().isEmpty()) {
                createInstanceStatusFile(computeNestInstanceId, true);
            } else {
                // 如果用户信息中没有计算巢实例ID，尝试使用传入的ecsInstanceId
                if (ecsInstanceId != null && !ecsInstanceId.trim().isEmpty()) {
                    createInstanceStatusFile(ecsInstanceId.trim(), true);
                } else {
                    logger.warn("实例 {} 没有关联的计算巢实例ID，无法创建状态文件", instanceId);
                }
            }
        } catch (ParseException e) {
            logger.error("过期时间格式错误，必须是 yyyy-MM-dd HH:mm:ss 格式: {}", expiredOnStr, e);
            JSONObject result = new JSONObject();
            result.put("error", "expiredOn格式错误，必须是yyyy-MM-dd HH:mm:ss格式");
            return result.toJSONString();
        } catch (Exception e) {
            logger.error("实例续费失败: {}", e.getMessage(), e);
            JSONObject result = new JSONObject();
            result.put("error", "实例续费失败: " + e.getMessage());
            return result.toJSONString();
        }

        // 记录验证成功的请求
        logger.info("Valid renewInstance request received at: {}", new Date());

        // 返回结果
        JSONObject result = new JSONObject();
        result.put("success", true);
        return result.toJSONString();
    }

    /**
     * 过期实例
     * @return
     */
    @RequestMapping(value="", params="action=expiredInstance")
    @ResponseBody
    public String expiredInstance() {
        // 记录传入请求
        logRequestDetails("expiredInstance");

        // 校验token
        if(!validateToken()) {
            JSONObject result = new JSONObject();
            result.put("error", "token is invalid");
            return result.toJSONString();
        }

        // 获取必需参数 - 按照文档要求，只有instanceId是必选参数
        String instanceId = request.getParameter("instanceId");
        if (instanceId == null || instanceId.trim().isEmpty()) {
            JSONObject result = new JSONObject();
            result.put("error", "instanceId is required");
            return result.toJSONString();
        }

        UserInfo userInfo = storageService.getUserByInstanceId(instanceId);
        if (userInfo != null) {
            userInfo.setStatus("EXPIRED");
            storageService.saveUser(userInfo);
            logger.info("实例标记为过期: {}", instanceId);

            // 获取该用户关联的计算巢实例ID
            String computeNestInstanceId = userInfo.getComputeNestInstanceId();
            
            if (computeNestInstanceId != null && !computeNestInstanceId.trim().isEmpty()) {
                // 使用计算巢实例ID创建无效状态标记文件
                createInstanceStatusFile(computeNestInstanceId, false);
                logger.info("为计算巢实例创建无效状态文件: {}", computeNestInstanceId);
            } else {
                // 如果还没有关联的计算巢实例ID，记录警告
                logger.warn("云市场实例 {} 尚未关联计算巢实例ID，无法创建状态文件", instanceId);
            }
        } else {
            logger.warn("实例不存在: {}", instanceId);
        }

        // 记录验证成功的请求
        logger.info("Valid expiredInstance request received at: {}", new Date());
        // 返回结果
        JSONObject result = new JSONObject();
        result.put("success", true);
        return result.toJSONString();
    }    /**
     * 释放实例
     * @return
     */
    @RequestMapping(value="", params="action=releaseInstance")
    @ResponseBody
    public String releaseInstance() {
        // 记录传入请求
        logRequestDetails("releaseInstance");

        // 校验token
        if(!validateToken()) {
            JSONObject result = new JSONObject();
            result.put("error", "token is invalid");
            return result.toJSONString();
        }

        // 获取必需参数 - 按照文档要求，instanceId和isRefund是必选参数
        String instanceId = request.getParameter("instanceId");
        String isRefundStr = request.getParameter("isRefund");
        
        if (instanceId == null || instanceId.trim().isEmpty()) {
            JSONObject result = new JSONObject();
            result.put("error", "instanceId is required");
            return result.toJSONString();
        }
        
        if (isRefundStr == null || isRefundStr.trim().isEmpty()) {
            JSONObject result = new JSONObject();
            result.put("error", "isRefund is required");
            return result.toJSONString();
        }
        
        // 验证isRefund参数值
        boolean isRefund;
        if ("true".equalsIgnoreCase(isRefundStr.trim())) {
            isRefund = true;
        } else if ("false".equalsIgnoreCase(isRefundStr.trim())) {
            isRefund = false;
        } else {
            JSONObject result = new JSONObject();
            result.put("error", "isRefund必须是true或false");
            return result.toJSONString();
        }

        UserInfo userInfo = storageService.getUserByInstanceId(instanceId);
        if (userInfo != null) {
            // 记录是否退款的信息
            logger.info("释放实例 {}，退款状态: {}", instanceId, isRefund);
            
            // 获取该用户关联的计算巢实例ID
            String computeNestInstanceId = userInfo.getComputeNestInstanceId();
            
            // 如果有传入ecsInstanceId参数，优先使用它
            String ecsInstanceId = request.getParameter("ecsInstanceId");
            if (ecsInstanceId != null && !ecsInstanceId.trim().isEmpty()) {
                computeNestInstanceId = ecsInstanceId.trim();
                logger.info("使用传入的ecsInstanceId: {}", computeNestInstanceId);
            }
            
            // 删除用户信息
            storageService.deleteUser(userInfo.getOrderBizId());
            logger.info("实例释放成功: {}", instanceId);

            // 如果有关联的计算巢实例ID，删除对应的状态标记文件
            if (computeNestInstanceId != null && !computeNestInstanceId.trim().isEmpty()) {
                deleteInstanceStatusFile(computeNestInstanceId);
                logger.info("删除计算巢实例状态文件: {}", computeNestInstanceId);
                        // 修复：触发一次nginx重载，确保移除配置
                scheduleDelayedReload(computeNestInstanceId);
            
            } else {
                // 如果还没有关联的计算巢实例ID，记录警告
                logger.warn("云市场实例 {} 尚未关联计算巢实例ID，无法删除状态文件", instanceId);
            }
        } else {
            logger.info("实例不存在，无需释放: {}", instanceId);
        }

        // 记录验证成功的请求
        logger.info("Valid releaseInstance request received at: {}", new Date());
        // 返回结果
        JSONObject result = new JSONObject();
        result.put("success", true);
        return result.toJSONString();
    }

    /**
     * 校验token
     * @return
     */
    private boolean validateToken() {
        String genToken = generateToken();
        String requestToken = request.getParameter("token");
        return genToken != null && genToken.equals(requestToken);
    }

    /**
     * 根据请求参数生成token
     * @return
     */
    private String generateToken() {
        Map<String, String[]> parameterMap = request.getParameterMap();
        String[] sortedKeys = (String[])parameterMap.keySet().toArray(new String[0]);
        Arrays.sort(sortedKeys);
        StringBuilder baseStringBuilder = new StringBuilder();
        for(String key : sortedKeys) {
            if(!"token".equals(key)) {
                baseStringBuilder.append(key).append("=").append(parameterMap.get(key)[0]).append("&");
            }
        }
        baseStringBuilder.append("key").append("=").append(SECRET_KEY);
        return md5(baseStringBuilder.toString());
    }

    /**
     * md5工具方法
     * @param s
     * @return
     */
    private static String md5(String s) {
        try {
            MessageDigest DIGESTER = MessageDigest.getInstance("MD5");
            byte[] digest = DIGESTER.digest(s.getBytes());
            return bytesToString(digest);
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
        return null;
    }

    private static String bytesToString(byte[] data) {
        char hexDigits[] = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd',
                'e', 'f'};
        char[] temp = new char[data.length * 2];
        for (int i = 0; i < data.length; i++) {
            byte b = data[i];
            temp[i * 2] = hexDigits[b >>> 4 & 0x0f];
            temp[i * 2 + 1] = hexDigits[b & 0x0f];
        }
        return new String(temp);
    }
    
    /**
     * 获取参数值（简化空值处理）
     */
    private String getParameter(String paramName) {
        String value = request.getParameter(paramName);
        return value != null ? value.trim() : "";
    }

    /**
     * 管理接口 - 查看虚拟用户（简化版）
     */
    @RequestMapping(value="/admin/virtual-users", method = RequestMethod.GET)
    @ResponseBody
    public String listVirtualUsers() {
        try {
            // 获取所有虚拟用户
            List<UserInfo> allUsers = storageService.getAllUsers();
            JSONObject result = new JSONObject();
            JSONArray virtualUsers = new JSONArray();
            int count = 0;
            
            for (UserInfo user : allUsers) {
                // 判断是否为虚拟用户（根据aliUid前缀）
                if (user.getAliUid() != null && user.getAliUid().startsWith("VIRTUAL_")) {
                    JSONObject userObj = new JSONObject();
                    userObj.put("computeNestInstanceId", user.getComputeNestInstanceId());
                    userObj.put("aliUid", user.getAliUid());
                    userObj.put("status", user.getStatus());
                    userObj.put("isValid", user.isValid());
                    userObj.put("isExpired", user.isExpired());
                    userObj.put("expiredOn", user.getExpiredOn());
                    virtualUsers.add(userObj);
                    count++;
                }
            }
            
            result.put("success", true);
            result.put("count", count);
            result.put("virtualUsers", virtualUsers);
            return result.toJSONString();
            
        } catch (Exception e) {
            logger.error("获取虚拟用户列表失败", e);
            JSONObject result = new JSONObject();
            result.put("success", false);
            result.put("error", e.getMessage());
            return result.toJSONString();
        }
    }

    /**
     * 管理接口 - 查看系统状态（简化版）
     */
    @RequestMapping(value="/admin/system-status", method = RequestMethod.GET)
    @ResponseBody
    public String getSystemStatus() {
        JSONObject result = new JSONObject();
        
        try {
            // 1. 统计状态文件
            File statusDir = new File("/etc/nginx/conf.d/instance_status/");
            JSONObject statusStats = new JSONObject();
            if (statusDir.exists() && statusDir.isDirectory()) {
                File[] statusFiles = statusDir.listFiles((d, name) -> name.endsWith(".conf"));
                int total = statusFiles != null ? statusFiles.length : 0;
                statusStats.put("total", total);
                
                // 显示几个示例
                JSONArray examples = new JSONArray();
                if (statusFiles != null && statusFiles.length > 0) {
                    for (int i = 0; i < Math.min(3, statusFiles.length); i++) {
                        File file = statusFiles[i];
                        String instanceId = file.getName().replace(".conf", "");
                        examples.add(instanceId);
                    }
                }
                statusStats.put("examples", examples);
            } else {
                statusStats.put("total", 0);
                statusStats.put("exists", false);
            }
            
            // 2. 用户统计
            List<UserInfo> allUsers = storageService.getAllUsers();
            int virtualCount = 0;
            for (UserInfo user : allUsers) {
                if (user.getAliUid() != null && user.getAliUid().startsWith("VIRTUAL_")) {
                    virtualCount++;
                }
            }
            
            JSONObject userStats = new JSONObject();
            userStats.put("total", allUsers.size());
            userStats.put("virtual", virtualCount);
            userStats.put("normal", allUsers.size() - virtualCount);
            
            // 3. 汇总
            result.put("success", true);
            result.put("timestamp", new Date().toString());
            result.put("statusFiles", statusStats);
            result.put("users", userStats);
            result.put("defaultExpiry", "2026-01-31 00:00:00");
            
        } catch (Exception e) {
            logger.error("获取系统状态失败", e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        
        return result.toJSONString();
    }

        /**
     * 管理接口 - 查看状态文件详情
     */
    @RequestMapping(value="/admin/status-files", method = RequestMethod.GET)
    @ResponseBody
    public String listStatusFiles() {
        JSONObject result = new JSONObject();
        
        try {
            File statusDir = new File("/etc/nginx/conf.d/instance_status/");
            JSONArray filesArray = new JSONArray();
            
            if (statusDir.exists() && statusDir.isDirectory()) {
                File[] statusFiles = statusDir.listFiles((d, name) -> name.endsWith(".conf"));
                
                if (statusFiles != null) {
                    for (File file : statusFiles) {
                        JSONObject fileObj = new JSONObject();
                        fileObj.put("name", file.getName());
                        fileObj.put("size", file.length());
                        fileObj.put("lastModified", new Date(file.lastModified()).toString());
                        
                        try {
                            // 读取文件内容
                            List<String> lines = Files.readAllLines(file.toPath());
                            if (!lines.isEmpty()) {
                                fileObj.put("content", lines.get(0));
                                
                                // 解析状态
                                String line = lines.get(0).trim();
                                if (line.contains(" ")) {
                                    String[] parts = line.split(" ");
                                    if (parts.length >= 2) {
                                        String status = parts[1].replace(";", "").trim();
                                        fileObj.put("status", status);
                                    }
                                }
                            }
                        } catch (Exception e) {
                            fileObj.put("error", e.getMessage());
                        }
                        
                        filesArray.add(fileObj);
                    }
                }
                
                result.put("success", true);
                result.put("count", filesArray.size());
                result.put("files", filesArray);
                result.put("directory", statusDir.getAbsolutePath());
            } else {
                result.put("success", false);
                result.put("error", "状态文件目录不存在: " + statusDir.getAbsolutePath());
            }
            
        } catch (Exception e) {
            logger.error("获取状态文件列表失败", e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        
        return result.toJSONString();
    }
}