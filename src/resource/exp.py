import numpy as np

# a = 2.94140625
# res = np.exp2(a)
# print(res) # 11.11000010

# a = 6.42968750
# res = np.log2(a)
# print(res) 

x = np.array([1, 2])
beta_plus = 0.25390625
beta_sub = 0.0
alpha_log2e = 2.57031250

xg = np.where(x >= 0, x - beta_plus, x + beta_sub)
xg_mul_const = xg * alpha_log2e
print(xg_mul_const)
xg_mul_const = 1.91406250
print(np.exp2(xg_mul_const))
a = 4.828125000
print(np.log2(a))
b = -2.20703125
print(np.exp2(b))

import torch
import torch.nn.functional as F

x = torch.Tensor([1, 2])
y = F.gelu(x)  # 默认使用 'none' 近似（精确版，基于 erf）
# 或指定近似方式：
y_approx = F.gelu(x, approximate='tanh')  # 使用 tanh 近似，计算更快
print(y)
print(y_approx)

x = 2
a = -1 * x * 1.702
a = np.exp2(a)
a = a + 1
a = np.log2(a)
a = 0 - a
a = np.exp2(a)
a = x * a
print(a)

a = (1 / (np.exp(-1.702 * x) + 1)) * x
print(a)