package com.isv.spi.services;

import com.isv.spi.models.UserInfo;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class StorageService {
    private static final Logger logger = LoggerFactory.getLogger(StorageService.class);
    // 使用orderBizId作为主键存储
    private final Map<String, UserInfo> userStore = new ConcurrentHashMap<>();
    
    // 按aliUid索引，一个aliUid可能有多个实例
    private final Map<String, List<String>> aliUidIndex = new ConcurrentHashMap<>();
    
    // 存储文件路径
    private static final String STORAGE_DIR = "opt/isv-spi-data/";
    private static final String STORAGE_FILE = STORAGE_DIR + "users.dat";
    
    public StorageService() {
        // 创建存储目录
        ensureStorageDirectory();
        // 加载已保存的用户数据
        loadFromFile();
        logger.info("StorageService 初始化完成，加载了 {} 条记录", userStore.size());
    }
    
    /**
     * 保存用户信息
     */
    public synchronized void saveUser(UserInfo userInfo) {
        String orderBizId = userInfo.getOrderBizId();
        String aliUid = userInfo.getAliUid();
        
        // 保存到主存储
        userStore.put(orderBizId, userInfo);
        
        // 更新aliUid索引
        List<String> orderList = aliUidIndex.computeIfAbsent(aliUid, k -> new ArrayList<>());
        if (!orderList.contains(orderBizId)) {
            orderList.add(orderBizId);
        }
        
        // 持久化到文件
        saveToFile();
        
        System.out.println("用户信息已保存: " + userInfo);
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
     * 根据computeNestInstanceId获取用户信息
     */
    public UserInfo getUserByComputeNestInstanceId(String computeNestInstanceId) {
        for (UserInfo user : userStore.values()) {
            if (computeNestInstanceId.equals(user.getComputeNestInstanceId())) {
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
            userInfo.setComputeNestInstanceId(computeNestInstanceId);
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
            
            saveToFile();
            System.out.println("用户信息已删除: " + orderBizId);
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
            System.out.println("存储目录已创建: " + STORAGE_DIR);
        } catch (IOException e) {
            System.err.println("创建存储目录失败: " + e.getMessage());
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
            
            oos.writeObject(storageData);
            System.out.println("用户数据已持久化到文件: " + STORAGE_FILE);
        } catch (IOException e) {
            System.err.println("保存用户数据失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 从文件加载
     */
    @SuppressWarnings("unchecked")
    private synchronized void loadFromFile() {
        File file = new File(STORAGE_FILE);
        if (!file.exists()) {
            System.out.println("用户数据文件不存在，将创建新文件");
            return;
        }
        
        try (ObjectInputStream ois = new ObjectInputStream(
                new FileInputStream(file))) {
            Map<String, Object> storageData = (Map<String, Object>) ois.readObject();
            
            userStore.clear();
            aliUidIndex.clear();
            
            Map<String, UserInfo> loadedStore = (Map<String, UserInfo>) storageData.get("userStore");
            Map<String, List<String>> loadedIndex = (Map<String, List<String>>) storageData.get("aliUidIndex");
            
            if (loadedStore != null) {
                userStore.putAll(loadedStore);
            }
            if (loadedIndex != null) {
                aliUidIndex.putAll(loadedIndex);
            }
            
            System.out.println("从文件加载了 " + userStore.size() + " 条用户记录");
        } catch (IOException | ClassNotFoundException e) {
            System.err.println("加载用户数据失败: " + e.getMessage());
            // 如果加载失败，清空存储
            userStore.clear();
            aliUidIndex.clear();
        }
    }
}