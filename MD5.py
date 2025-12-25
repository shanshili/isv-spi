#!/usr/bin/env python3
"""
SPI Token生成器 - 简洁版
直接修改参数生成token
"""

import hashlib

# 云市场分配的秘钥
SECRET_KEY = ""

# ==============================
# 在这里修改你的参数
# ==============================
def get_params():
    """在这里定义你的参数"""
    params = {
        'action': 'createInstance',
        'aliUid': '1041031108983109',
        'orderBizId': '122779388',
        'productCode': 'testProduct',
        'expiredOn': '2026-01-25 00:00:00',
        'package_version': 'yuncode6661200001',
        'orderId': '269326581310319',
        'skuId': 'yuncode6661200001',
        'trial': 'true',
    }
    return params

def generate_token(params):
    """生成token"""
    # 排除token参数
    if 'token' in params:
        del params['token']
    
    # 按参数名排序
    sorted_keys = sorted(params.keys())
    
    # 构建参数字符串
    base_string = ""
    for key in sorted_keys:
        value = params[key]
        base_string += f"{key}={value}&"
    
    base_string += f"key={SECRET_KEY}"
    
    # 计算MD5
    md5_hash = hashlib.md5(base_string.encode('utf-8')).hexdigest()
    return md5_hash, base_string

def main():
    print("SPI Token生成器")
    print("=" * 50)
    
    # 获取参数
    params = get_params()
    
    print("参数列表:")
    for key, value in params.items():
        print(f"  {key}: {value}")
    
    print("\n" + "=" * 50)
    
    # 生成token
    token, base_string = generate_token(params)
    
    print(f"生成的token: {token}")
    print("\n用于生成token的字符串:")
    print(base_string)
    
    print("\n" + "=" * 50)
    
    # 生成curl命令示例
    print("curl命令示例:")
    curl_params = []
    for key, value in params.items():
        # 对值进行URL编码
        encoded_value = str(value).replace(' ', '%20')
        curl_params.append(f"{key}={encoded_value}")
    
    curl_params.append(f"token={token}")
    curl_command = f"curl -X POST 'https://spi.tashan.chat' -d '{'&'.join(curl_params)}'"
    print(curl_command)

if __name__ == "__main__":
    main()