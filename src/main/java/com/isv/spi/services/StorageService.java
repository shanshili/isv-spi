package com.isv.spi.services;

import com.isv.spi.models.UserInfo;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class StorageService {
    private static final Logger logger = LoggerFactory.getLogger(StorageService.class);
    // 使用orderBizId作为主键存储
    private final Map<String, UserInfo> userStore = new ConcurrentHashMap<>();
    
    // 按aliUid索引，一个aliUid可能有多个实例
    private final Map<String, List<String>> aliUidIndex = new ConcurrentHashMap<>();
    
    // 新增：按computeNestInstanceId索引（早期用户使用）
    private final Map<String, String> computeNestIndex = new ConcurrentHashMap<>();

    // 早期用户统一配置
    private static final String VIRTUAL_ALIUID_PREFIX = "VIRTUAL_"; // 虚拟用户前缀
    private static final Date DEFAULT_EXPIRY_DATE; // 默认过期时间
    
    // 存储文件路径
    private static final String STORAGE_DIR = "/opt/isv-spi-data/";
    private static final String STORAGE_FILE = STORAGE_DIR + "users.dat";

    static {
        try {
            // 设置默认过期时间为2026-01-31 00:00:00
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            DEFAULT_EXPIRY_DATE = sdf.parse("2026-01-31 00:00:00");
        } catch (Exception e) {
            throw new RuntimeException("初始化默认过期时间失败", e);
        }
    }
    
    public StorageService() {
        // 创建存储目录
        ensureStorageDirectory();
        // 加载已保存的用户数据
        loadFromFile();
        logger.info("StorageService 初始化完成，加载了 {} 条记录", userStore.size());
        
        // 初始化早期用户（从状态文件读取）
        initEarlyUsersFromStatusFiles();
    }
    
    /**
     * 从状态文件初始化早期用户
     */
    private void initEarlyUsersFromStatusFiles() {
        String statusDir = "/etc/nginx/conf.d/instance_status/";
        File dir = new File(statusDir);
        
        logger.info("开始初始化早期用户，状态文件目录: {}", statusDir);
        logger.info("目录是否存在: {}", dir.exists());
        logger.info("是否是目录: {}", dir.isDirectory());
        
        if (!dir.exists() || !dir.isDirectory()) {
            logger.info("状态文件目录不存在: {}", statusDir);
            return;
        }
        
        File[] statusFiles = dir.listFiles((d, name) -> name.endsWith(".conf"));
        if (statusFiles == null || statusFiles.length == 0) {
            logger.info("状态目录为空，没有找到 .conf 文件");
            return;
        }
        
        logger.info("找到 {} 个状态文件", statusFiles.length);
        
        // 列出所有状态文件（用于调试）
        for (File file : statusFiles) {
            logger.debug("状态文件: {}", file.getName());
        }
        
        int earlyUserCount = 0;
        int updatedCount = 0;
        int skippedCount = 0;
        
        for (File statusFile : statusFiles) {
            String computeNestInstanceId = null;
            try {
                String fileName = statusFile.getName();
                computeNestInstanceId = fileName.substring(0, fileName.length() - 5); // 去掉 .conf
                
                logger.debug("处理状态文件: {} -> 实例ID: {}", fileName, computeNestInstanceId);
                
                // 1. 检查是否已存在此计算巢实例的用户
                UserInfo existingUser = getUserByComputeNestInstanceId(computeNestInstanceId);
                if (existingUser != null) {
                    logger.debug("用户已存在，跳过创建: {}", computeNestInstanceId);
                    skippedCount++;
                    continue;
                }
                
                // 2. 读取状态文件内容
                boolean isValid = readStatusFromFile(statusFile);
                
                // 3. 创建早期用户信息
                UserInfo earlyUser = createEarlyUser(computeNestInstanceId, isValid);
                
                // 4. 保存早期用户
                saveUser(earlyUser);
                earlyUserCount++;
                
                logger.info("创建早期用户成功: {} (状态: {})", computeNestInstanceId, isValid);
                
            } catch (Exception e) {
                logger.error("初始化早期用户状态文件失败: {} - {}", 
                    computeNestInstanceId, e.getMessage(), e);
            }
        }
        
        logger.info("从状态文件初始化完成: 新增 {} 个早期用户, 跳过 {} 个已存在用户, 总计处理 {} 个状态文件", 
                earlyUserCount, skippedCount, statusFiles.length);
    }
    /**
     * 从状态文件读取状态
     */
    private boolean readStatusFromFile(File statusFile) throws IOException {
        List<String> lines = Files.readAllLines(statusFile.toPath());
        if (lines.isEmpty()) {
            return false;
        }
        
        String line = lines.get(0).trim();
        // 支持格式: instanceId true; 或 instanceId false;
        if (line.contains(" ")) {
            String[] parts = line.split(" ");
            if (parts.length >= 2) {
                String statusStr = parts[1].replace(";", "").trim();
                return "true".equalsIgnoreCase(statusStr);
            }
        }
        return false;
    }
    
    /**
     * 创建早期用户
     */
    private UserInfo createEarlyUser(String computeNestInstanceId, boolean isValid) {
        UserInfo earlyUser = new UserInfo();
        
        // 生成唯一ID
        String uniqueId = UUID.randomUUID().toString().substring(0, 8);
        
        // 设置虚拟信息
        earlyUser.setOrderBizId(VIRTUAL_ALIUID_PREFIX + computeNestInstanceId + "_" + uniqueId);
        earlyUser.setAliUid(VIRTUAL_ALIUID_PREFIX + computeNestInstanceId);
        earlyUser.setProductCode("EARLY_USER_PRODUCT");
        earlyUser.setPackageVersion("1.0");
        earlyUser.setOrderId("EARLY_ORDER_" + computeNestInstanceId);
        earlyUser.setInstanceId("EARLY_INSTANCE_" + computeNestInstanceId);
        earlyUser.setComputeNestInstanceId(computeNestInstanceId);
        earlyUser.setTrial(false);
        earlyUser.setStatus(isValid ? "ACTIVE" : "EXPIRED");
        earlyUser.setCreateTime(new Date());
        earlyUser.setExpiredOn(DEFAULT_EXPIRY_DATE);
        
        return earlyUser;
    }
    
    /**
     * 更新现有用户（如果需要）
     */
    private boolean updateExistingUserIfNeeded(UserInfo existingUser, File statusFile) throws IOException {
        boolean currentStatus = readStatusFromFile(statusFile);
        boolean shouldBeActive = currentStatus && !existingUser.isExpired();
        String shouldBeStatus = shouldBeActive ? "ACTIVE" : "EXPIRED";
        
        // 如果状态不一致，则更新
        if (!shouldBeStatus.equals(existingUser.getStatus())) {
            existingUser.setStatus(shouldBeStatus);
            saveUser(existingUser); // 会触发持久化
            logger.info("更新用户状态: {} -> {}", existingUser.getComputeNestInstanceId(), shouldBeStatus);
            return true;
        }
        
        // 如果是虚拟用户但过期时间不是默认值，更新为默认值
        if (existingUser.getAliUid().startsWith(VIRTUAL_ALIUID_PREFIX) && 
            !DEFAULT_EXPIRY_DATE.equals(existingUser.getExpiredOn())) {
            existingUser.setExpiredOn(DEFAULT_EXPIRY_DATE);
            saveUser(existingUser);
            logger.info("更新虚拟用户过期时间: {}", existingUser.getComputeNestInstanceId());
            return true;
        }
        
        return false;
    }
    
    /**
     * 保存用户信息（覆盖父类方法，确保索引更新）
     */
    public synchronized void saveUser(UserInfo userInfo) {
        String orderBizId = userInfo.getOrderBizId();
        String aliUid = userInfo.getAliUid();
        String oldComputeNestId = null;
        
        // 如果已存在相同orderBizId的用户，获取旧的computeNestInstanceId
        UserInfo existingUser = userStore.get(orderBizId);
        if (existingUser != null) {
            oldComputeNestId = existingUser.getComputeNestInstanceId();
        }
        
        // 如果是虚拟用户（aliUid以VIRTUAL_开头），设置虚拟标志
        if (aliUid != null && aliUid.startsWith(VIRTUAL_ALIUID_PREFIX)) {
            userInfo.setVirtualUser(true);
            logger.debug("标记为虚拟用户: {}", aliUid);
        }
        
        // 保存到主存储
        userStore.put(orderBizId, userInfo);
        
        // 更新aliUid索引
        List<String> orderList = aliUidIndex.computeIfAbsent(aliUid, k -> new ArrayList<>());
        if (!orderList.contains(orderBizId)) {
            orderList.add(orderBizId);
        }
        
        // 更新computeNestInstanceId索引
        String computeNestInstanceId = userInfo.getComputeNestInstanceId();
        if (computeNestInstanceId != null && !computeNestInstanceId.trim().isEmpty()) {
            // 移除旧的索引（如果存在）
            if (oldComputeNestId != null && !oldComputeNestId.isEmpty() && 
                !oldComputeNestId.equals(computeNestInstanceId)) {
                computeNestIndex.remove(oldComputeNestId);
            }
            computeNestIndex.put(computeNestInstanceId, orderBizId);
        }
        
        // 持久化到文件
        saveToFile();
        
        logger.debug("用户信息已保存: {}", userInfo.getOrderBizId());
    }
    
    /**
     * 根据computeNestInstanceId获取用户信息
     */
    public UserInfo getUserByComputeNestInstanceId(String computeNestInstanceId) {
        String orderBizId = computeNestIndex.get(computeNestInstanceId);
        if (orderBizId != null) {
            return userStore.get(orderBizId);
        }
        
        // 如果没有在索引中找到，遍历查找（兼容旧数据）
        for (UserInfo user : userStore.values()) {
            if (computeNestInstanceId.equals(user.getComputeNestInstanceId())) {
                // 更新索引
                computeNestIndex.put(computeNestInstanceId, user.getOrderBizId());
                return user;
            }
        }
        return null;
    }
    
    /**
     * 更新用户aliUid（用于虚拟用户更新为真实用户）
     */
    public synchronized void updateUserAliUid(String orderBizId, String newAliUid) {
        UserInfo userInfo = userStore.get(orderBizId);
        if (userInfo != null) {
            String oldAliUid = userInfo.getAliUid();
            
            // 如果aliUid已经相同，不需要更新
            if (oldAliUid.equals(newAliUid)) {
                return;
            }
            
            // 更新aliUid索引
            List<String> oldOrderList = aliUidIndex.get(oldAliUid);
            if (oldOrderList != null) {
                oldOrderList.remove(orderBizId);
                if (oldOrderList.isEmpty()) {
                    aliUidIndex.remove(oldAliUid);
                }
            }
            
            // 设置新的aliUid
            userInfo.setAliUid(newAliUid);
            
            // 添加到新的aliUid索引
            List<String> newOrderList = aliUidIndex.computeIfAbsent(newAliUid, k -> new ArrayList<>());
            if (!newOrderList.contains(orderBizId)) {
                newOrderList.add(orderBizId);
            }
            
            saveToFile();
            logger.info("更新用户aliUid: {} -> {} (实例ID: {})", 
                       oldAliUid, newAliUid, userInfo.getComputeNestInstanceId());
        }
    }
    
    /**
     * 判断是否为虚拟用户
     */
    public boolean isVirtualUser(UserInfo userInfo) {
        return userInfo != null && 
               userInfo.getAliUid() != null && 
               userInfo.getAliUid().startsWith(VIRTUAL_ALIUID_PREFIX);
    }
    
    /**
     * 获取所有虚拟用户
     */
    public List<UserInfo> getAllVirtualUsers() {
        List<UserInfo> virtualUsers = new ArrayList<>();
        for (UserInfo user : userStore.values()) {
            if (isVirtualUser(user)) {
                virtualUsers.add(user);
            }
        }
        return virtualUsers;
    }  
    /**
     * 根据orderBizId获取用户信息
     */
    public UserInfo getUserByOrderBizId(String orderBizId) {
        return userStore.get(orderBizId);
    }
    
    /**
     * 根据aliUid获取所有用户信息
     */
    public List<UserInfo> getUsersByAliUid(String aliUid) {
        List<String> orderIds = aliUidIndex.get(aliUid);
        if (orderIds == null || orderIds.isEmpty()) {
            return new ArrayList<>();
        }
        
        List<UserInfo> users = new ArrayList<>();
        for (String orderId : orderIds) {
            UserInfo user = userStore.get(orderId);
            if (user != null) {
                users.add(user);
            }
        }
        return users;
    }
    
    /**
     * 根据instanceId获取用户信息
     */
    public UserInfo getUserByInstanceId(String instanceId) {
        for (UserInfo user : userStore.values()) {
            if (instanceId.equals(user.getInstanceId())) {
                return user;
            }
        }
        return null;
    }

    /**
     * 更新用户的computeNestInstanceId
     */
    public synchronized void updateComputeNestInstanceId(String orderBizId, String computeNestInstanceId) {
        UserInfo userInfo = userStore.get(orderBizId);
        if (userInfo != null) {
            // 移除旧的索引
            String oldComputeNestId = userInfo.getComputeNestInstanceId();
            if (oldComputeNestId != null && !oldComputeNestId.isEmpty()) {
                computeNestIndex.remove(oldComputeNestId);
            }
            
            // 设置新的计算巢实例ID
            userInfo.setComputeNestInstanceId(computeNestInstanceId);
            
            // 更新索引
            if (computeNestInstanceId != null && !computeNestInstanceId.trim().isEmpty()) {
                computeNestIndex.put(computeNestInstanceId, orderBizId);
            }
            
            saveToFile(); // 持久化到文件
            logger.info("更新用户 {} 的computeNestInstanceId为: {}", orderBizId, computeNestInstanceId);
        }
    }
    
    /**
     * 删除用户信息
     */
    public synchronized void deleteUser(String orderBizId) {
        UserInfo userInfo = userStore.get(orderBizId);
        if (userInfo != null) {
            // 从主存储删除
            userStore.remove(orderBizId);
            
            // 从aliUid索引删除
            String aliUid = userInfo.getAliUid();
            List<String> orderList = aliUidIndex.get(aliUid);
            if (orderList != null) {
                orderList.remove(orderBizId);
                if (orderList.isEmpty()) {
                    aliUidIndex.remove(aliUid);
                }
            }
            
            // 从computeNest索引删除
            String computeNestInstanceId = userInfo.getComputeNestInstanceId();
            if (computeNestInstanceId != null && !computeNestInstanceId.isEmpty()) {
                computeNestIndex.remove(computeNestInstanceId);
            }
            
            saveToFile();
            logger.info("用户信息已删除: {}", orderBizId);
        }
    }
    
    /**
     * 获取所有用户
     */
    public List<UserInfo> getAllUsers() {
        return new ArrayList<>(userStore.values());
    }
    
    /**
     * 获取所有aliUid
     */
    public Set<String> getAllAliUids() {
        return new HashSet<>(aliUidIndex.keySet());
    }
    
    /**
     * 确保存储目录存在
     */
    private void ensureStorageDirectory() {
        try {
            Files.createDirectories(Paths.get(STORAGE_DIR));
            logger.info("存储目录已创建: {}", STORAGE_DIR);
        } catch (IOException e) {
            logger.error("创建存储目录失败: {}", e.getMessage());
        }
    }
    
    /**
     * 保存到文件
     */
    private synchronized void saveToFile() {
        try (ObjectOutputStream oos = new ObjectOutputStream(
                new FileOutputStream(STORAGE_FILE))) {
            // 保存所有数据到文件
            Map<String, Object> storageData = new HashMap<>();
            storageData.put("userStore", new HashMap<>(userStore));
            storageData.put("aliUidIndex", new HashMap<>(aliUidIndex));
            storageData.put("computeNestIndex", new HashMap<>(computeNestIndex));
            
            oos.writeObject(storageData);
            logger.debug("用户数据已持久化到文件: {}", STORAGE_FILE);
        } catch (IOException e) {
            logger.error("保存用户数据失败: {}", e.getMessage(), e);
        }
    }
    
    /**
     * 从文件加载
     */
    @SuppressWarnings("unchecked")
    private synchronized void loadFromFile() {
        File file = new File(STORAGE_FILE);
        if (!file.exists()) {
            logger.info("用户数据文件不存在，将创建新文件");
            return;
        }
        
        try (ObjectInputStream ois = new ObjectInputStream(
                new FileInputStream(file))) {
            Map<String, Object> storageData = (Map<String, Object>) ois.readObject();
            
            userStore.clear();
            aliUidIndex.clear();
            computeNestIndex.clear();
            
            Map<String, UserInfo> loadedStore = (Map<String, UserInfo>) storageData.get("userStore");
            Map<String, List<String>> loadedIndex = (Map<String, List<String>>) storageData.get("aliUidIndex");
            Map<String, String> loadedComputeNestIndex = (Map<String, String>) storageData.get("computeNestIndex");
            
            if (loadedStore != null) {
                userStore.putAll(loadedStore);
            }
            if (loadedIndex != null) {
                aliUidIndex.putAll(loadedIndex);
            }
            if (loadedComputeNestIndex != null) {
                computeNestIndex.putAll(loadedComputeNestIndex);
            }
            
            logger.info("从文件加载了 {} 条用户记录", userStore.size());
        } catch (IOException | ClassNotFoundException e) {
            logger.error("加载用户数据失败: {}", e.getMessage(), e);
            // 如果加载失败，清空存储
            userStore.clear();
            aliUidIndex.clear();
            computeNestIndex.clear();
        }
    }
}