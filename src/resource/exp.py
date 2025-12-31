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
