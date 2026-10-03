#version 330 core

layout (location = 0) in vec4 Position;
layout (location = 1) in vec2 Uv;
layout (location = 2) in vec4 P1;
layout (location = 3) in vec4 P2;
layout (location = 4) in vec4 ColorA;
layout (location = 5) in vec4 ColorB;

layout (std140) uniform MeshData {
    mat4 u_Proj;
    mat4 u_ModelView;
};

out vec2 v_Uv;
out vec4 v_P1;
out vec4 v_P2;
out vec4 v_ColorA;
out vec4 v_ColorB;

void main() {
    gl_Position = u_Proj * u_ModelView * Position;

    v_Uv = Uv;
    v_P1 = P1;
    v_P2 = P2;
    v_ColorA = ColorA;
    v_ColorB = ColorB;
}
